package moe.yanhe.xmsound.hook.milink

import android.bluetooth.BluetoothDevice
import android.content.Intent
import moe.yanhe.xmsound.hook.HeadsetStateCache
import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.hook.SafeHookContext
import moe.yanhe.xmsound.hook.callMethod
import moe.yanhe.xmsound.hook.getObjectField

/**
 * Makes MiLink - the Fusion Device Center's runtime - report the WF-1000XM5 as a live headset.
 *
 * Spoofing `checkSupport` on the Bluetooth side renders the settings page, but the **noise control
 * buttons stay inert and show "请连接并佩戴耳机"** until MiLink also believes the headset is
 * present and worn. That state lives here:
 *
 * ```
 * MxBluetoothManager / MxBluetoothService
 *   checkIsMiTWS(BluetoothDevice) -> 1        getDeviceId(BluetoothDevice)   -> device id
 *   getBatteryLevel(BluetoothDevice)          getAncState(BluetoothDevice)   -> 0 off / 1 nc / 2 ambient
 *   getDeviceRunInfo(BluetoothDevice) -> 0    getWearStatus(BluetoothDevice) -> "0,0"   <- the gate
 *   isLeAudio(BluetoothDevice) -> false       openAnc / closeAnc / openTransparent(BluetoothDevice)
 *
 * AncBatteryController
 *   getBatteryLevelCache(BluetoothDevice) -> [case, left, right, caseChg, leftChg, rightChg]
 *   getHeadsetPropertyBlock(BluetoothDevice) ->  percent   getFindRingState(BluetoothDevice) -> -1
 *   setAncStateBlock(BluetoothDevice, Int)  -> intercept and forward
 * ```
 *
 * Values come from the module's own process through the state provider, and ANC commands are handed
 * back the same way.
 */
class MiLinkHeadsetSpoofHook : SafeHookContext() {

    override val tag: String get() = "MiLinkSpoof"

    private val targetAddresses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The controller whose UI listener has to be poked after a change. HyperOS does not re-read
     * `getAncState` on its own - without this the buttons work but the highlight never moves.
     */
    @Volatile
    private var lastController: Any? = null

    @Volatile
    private var lastDevice: BluetoothDevice? = null

    @Volatile
    private var notifyHandler: android.os.Handler? = null

    /** Runs the UI notification off the main thread so a slow listener cannot freeze the card. */
    private val notifyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xmsound-notify").apply { isDaemon = true }
    }

    override fun onHook() {
        notifyHandler = android.os.Handler(android.os.Looper.getMainLooper())
        HeadsetStateCache.start()
        MX_CLASSES.forEach { className ->
            hookDeviceResult(className, "checkIsMiTWS") { 1 }
            hookDeviceResult(className, "getDeviceId") { FAKE_DEVICE_ID }
            hookDeviceResult(className, "getBatteryLevel") { batteryPercent() }
            hookDeviceResult(className, "getAncState") { ancState() }
            hookDeviceResult(className, "getDeviceRunInfo") { 0 }
            // The gate for the settings page's noise-control buttons.
            hookDeviceResult(className, "getWearStatus") { WEAR_STATUS }
            hookDeviceResult(className, "isLeAudio") { false }

            hookAncCommand(className, "openAnc", ANTI_NOISE, MIUI_ANC_NC)
            hookAncCommand(className, "closeAnc", SonyAnc.OFF, MIUI_ANC_OFF)
            hookAncCommand(className, "openTransparent", SonyAnc.AMBIENT, MIUI_ANC_AMBIENT)

            hookStringResult(className, "isMiTWS") { true }
            hookStringResult(className, "isSupportAudioSwitch") { 0 }
            hookStringResult(className, "getRingFindState") { false }
        }

        hookDeviceResult(ANC_CONTROLLER, "getDeviceId") { FAKE_DEVICE_ID }
        hookDeviceResult(ANC_CONTROLLER, "getBatteryLevelCache") { batteryList() }
        hookDeviceResult(ANC_CONTROLLER, "getAncState") { ancState() }
        hookDeviceResult(ANC_CONTROLLER, "getHeadsetPropertyBlock") { batteryPercent() }
        hookDeviceResult(ANC_CONTROLLER, "getFindRingState") { -1 }
        hookStringResult(ANC_CONTROLLER, "getSwitchState") { 0 }
        hookAncStateBlock()

        // HeadsetInfo carries the same data as immutable components for the UI list.
        hookNoArg(HEADSET_INFO, "getDeviceId") { FAKE_DEVICE_ID }
        hookNoArg(HEADSET_INFO, "component3") { FAKE_DEVICE_ID }
        hookNoArg(HEADSET_INFO, "getPowers") { batteryList() }
        hookNoArg(HEADSET_INFO, "component4") { batteryList() }
        hookNoArg(HEADSET_INFO, "getMode") { ancState() }
        hookNoArg(HEADSET_INFO, "component5") { ancState() }
        hookNoArg(HEADSET_INFO, "getSwitchState") { 0 }
        hookNoArg(HEADSET_INFO, "component8") { 0 }
        hookNoArg(HEADSET_INFO, "getFindRingState") { -1 }
        hookNoArg(HEADSET_INFO, "component11") { -1 }

        HookLog.i(tag, "MiLink headset spoof installed in $packageName")
    }

    // ---------------------------------------------------------------- state

    private fun state() = queryHeadsetState()

    private fun ancState(): Int = state()?.getInt(KEY_ANC_STATE, 0) ?: 0

    /** MiLink reports a single figure; the lower of the two connected buds, or 0. */
    private fun batteryPercent(): Int {
        val bundle = state() ?: return 0
        val levels = listOf(bundle.getInt(KEY_LEFT, -1), bundle.getInt(KEY_RIGHT, -1))
            .filter { it in 0..100 }
        return levels.minOrNull() ?: 0
    }

    /** `[case, left, right, caseCharging, leftCharging, rightCharging]`, -1 for an absent part. */
    private fun batteryList(): List<Int> {
        val bundle = state() ?: return listOf(-1, -1, -1, 0, 0, 0)
        return listOf(
            bundle.getInt(KEY_CASE, -1),
            bundle.getInt(KEY_LEFT, -1),
            bundle.getInt(KEY_RIGHT, -1),
            if (bundle.getBoolean(KEY_CASE_CHARGING, false)) 1 else 0,
            if (bundle.getBoolean(KEY_LEFT_CHARGING, false)) 1 else 0,
            if (bundle.getBoolean(KEY_RIGHT_CHARGING, false)) 1 else 0,
        )
    }

    // ---------------------------------------------------------------- installers

    private fun hookDeviceResult(className: String, methodName: String, value: () -> Any?) {
        install("$className#$methodName(BluetoothDevice)") {
            val method = findMethodAnywhere(className, methodName, BluetoothDevice::class.java)
            hookAfter(method) {
                val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookAfter
                if (!isTargetDevice(device)) return@hookAfter
                if (instance != null) lastController = instance
                lastDevice = device
                result = value()
            }
        }
    }

    private fun hookStringResult(className: String, methodName: String, value: () -> Any?) {
        install("$className#$methodName(String)") {
            val method = findMethodAnywhere(className, methodName, String::class.java)
            hookAfter(method) {
                val address = args.getOrNull(0) as? String
                if (!isTargetAddress(address)) return@hookAfter
                result = value()
            }
        }
    }

    private fun hookNoArg(className: String, methodName: String, value: () -> Any?) {
        install("$className#$methodName()") {
            val method = findClass(className).declaredMethods
                .firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("$className#$methodName")
            hookAfter(method) {
                if (!isTargetHeadsetInfo(instance)) return@hookAfter
                result = value()
            }
        }
    }

    private fun hookAncCommand(className: String, methodName: String, sonyMode: Int, miuiState: Int) {
        install("$className#$methodName") {
            val method = findMethodAnywhere(className, methodName, BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.getOrNull(0) as? BluetoothDevice
                if (!isTargetDevice(device)) return@hookBefore
                if (instance != null) lastController = instance
                lastDevice = device
                HookLog.i(tag, "$methodName(${device?.address}) -> sony mode $sonyMode")
                forwardMode(sonyMode)
                // MiLink expects the resulting state back, so the UI switches immediately.
                result = miuiState
                schedulePropertyNotify()
            }
        }
    }

    /** `setAncStateBlock(BluetoothDevice, Int)` is how the UI actually commits a mode change. */
    private fun hookAncStateBlock() {
        install("$ANC_CONTROLLER#setAncStateBlock") {
            val method = findMethodAnywhere(
                ANC_CONTROLLER,
                "setAncStateBlock",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!,
            )
            hookBefore(method) {
                val device = args.getOrNull(0) as? BluetoothDevice
                val miuiMode = args.getOrNull(1) as? Int
                if (!isTargetDevice(device)) return@hookBefore
                if (instance != null) lastController = instance
                lastDevice = device
                val sony = sonyModeFor(miuiMode)
                HookLog.i(tag, "setAncStateBlock miui=$miuiMode -> sony=$sony device=${device?.address}")
                sony?.let { forwardMode(it) }
                result = ancState()
                if (sony != null) schedulePropertyNotify()
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** MiLink: 1 = noise cancelling, 2 = ambient, anything else = off. */
    private fun sonyModeFor(miuiMode: Int?): Int? = when (miuiMode) {
        MIUI_ANC_NC -> SonyAnc.NOISE_CANCELLING
        MIUI_ANC_AMBIENT -> SonyAnc.AMBIENT
        MIUI_ANC_OFF -> SonyAnc.OFF
        else -> null
    }

    /**
     * Tell the UI a property changed.
     *
     * HyperOS caches the noise-control state and will not re-read `getAncState` on its own, so the
     * highlight only moves once `headsetPropertyChangeListener` is invoked. `updateType` follows the
     * reference implementation: 8 = noise control, 4 = battery / general state.
     */
    private fun schedulePropertyNotify() {
        // Ask for fresh state off the calling thread: this may run on the UI thread, and the
        // refresh is a binder round trip.
        HeadsetStateCache.refreshAsync()
        // The listener re-renders the whole card, so it is invoked off the main thread with a
        // delay long enough for the headset to have applied the change.
        notifyExecutor.execute {
            runCatching { Thread.sleep(NOTIFY_DELAY_MS) }
            notifyPropertyChange(8)
            notifyPropertyChange(4)
        }
    }

    private fun notifyPropertyChange(updateType: Int) {
        val controller = lastController
        val device = lastDevice
        if (controller == null || device == null) {
            HookLog.w(tag, "no controller captured yet, cannot notify type=$updateType")
            return
        }
        val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }.getOrNull()
        if (listener == null) {
            HookLog.w(tag, "headsetPropertyChangeListener missing on ${controller.javaClass.name}")
            return
        }
        runCatching { callMethod(listener, "invoke", device, updateType) }
            .onSuccess { HookLog.i(tag, "notified property change type=$updateType") }
            .onFailure { HookLog.w(tag, "notify type=$updateType failed: ${it.message}") }
    }

    private fun forwardMode(sonyMode: Int) {        val name = when (sonyMode) {
            SonyAnc.OFF -> "off"
            SonyAnc.NOISE_CANCELLING -> "nc"
            SonyAnc.AMBIENT -> "ambient"
            else -> return
        }
        // "openAnc" carries a raw mode; keep the legacy value working too.
        val mapped = if (sonyMode == ANTI_NOISE) "nc" else name
        val context = appContext() ?: return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_SET_NOISE)
                    .setPackage(MODULE_PACKAGE)
                    .putExtra("mode", mapped),
            )
            HookLog.i(tag, "forwarded mode=$mapped")
        }.onFailure { HookLog.w(tag, "forward failed: ${it.message}") }
    }

    private fun isTargetDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = device.address.orEmpty()
        if (address.isNotEmpty() && targetAddresses.contains(address.uppercase())) return true
        val name = runCatching { device.name }.getOrNull().orEmpty()
        val matched = TARGET_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
        if (matched && address.isNotEmpty()) targetAddresses.add(address.uppercase())
        return matched
    }

    private fun isTargetAddress(address: String?): Boolean =
        !address.isNullOrEmpty() && targetAddresses.contains(address.uppercase())

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { name ->
            val address = runCatching { callMethod(info, name) as? String }.getOrNull()
            if (isTargetAddress(address)) return true
        }
        // Address is not known yet (MiLink may build the model before the device is seen): fall
        // back to accepting the spoofed device id.
        val id = runCatching { callMethod(info, "getDeviceId") as? String }.getOrNull()
        return id == FAKE_DEVICE_ID || targetAddresses.isEmpty()
    }

    private companion object {
        const val ANC_CONTROLLER = "com.miui.headset.runtime.AncBatteryController"
        const val HEADSET_INFO = "com.miui.headset.api.HeadsetInfo"

        val MX_CLASSES = listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
        )

        val TARGET_NAME_PREFIXES = listOf("WF-1000XM5", "WH-1000XM5", "WF-1000XM4")

        const val FAKE_DEVICE_ID = "01010607"

        /** MiLink's wear status string; without it the noise-control buttons refuse to act. */
        const val WEAR_STATUS = "0,0"

        const val MIUI_ANC_OFF = 0
        const val MIUI_ANC_NC = 1
        const val MIUI_ANC_AMBIENT = 2

        object SonyAnc {
            const val OFF = 0
            const val NOISE_CANCELLING = 1
            const val AMBIENT = 2
        }

        /** Legacy alias used by `openAnc`, which the reference maps to noise cancelling. */
        const val ANTI_NOISE = SonyAnc.NOISE_CANCELLING

        const val MODULE_PACKAGE = "moe.yanhe.xmsound"
        const val ACTION_SET_NOISE = "moe.yanhe.xmsound.action.SET_NOISE"

        const val KEY_LEFT = "left"
        const val KEY_RIGHT = "right"
        const val KEY_CASE = "case"
        const val KEY_LEFT_CHARGING = "leftCharging"
        const val KEY_RIGHT_CHARGING = "rightCharging"
        const val KEY_CASE_CHARGING = "caseCharging"
        const val KEY_ANC_STATE = "ancState"

        /** Delay before poking the UI listener, to let the headset actually apply the change. */
        const val NOTIFY_DELAY_MS = 1_200L
    }
}
