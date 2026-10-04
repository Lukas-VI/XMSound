package moe.yanhe.xmsound.hook.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.lang.reflect.Method
import moe.yanhe.xmsound.hook.HeadsetStateCache
import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.hook.SafeHookContext
import moe.yanhe.xmsound.hook.bluetooth.strongtoast.MiuiStrongToast
import moe.yanhe.xmsound.hook.callMethod

/**
 * Makes HyperOS treat a Sony WF-1000XM5 like a supported headset.
 *
 * The Bluetooth settings page asks the Xiaomi headset Binder
 * (`com.android.bluetooth.ble.app.IMiuiHeadsetService`) about the connected device and gets a
 * "not supported" answer, so it offers no battery detail and no noise control.
 *
 * The implementation class is R8-obfuscated (`com.android.bluetooth.ble.app.headset.v`), but the
 * **AIDL method names are not** - they are part of the IPC contract:
 *
 * ```
 * String  checkSupport(BluetoothDevice)      String  getDeviceInfo(String)
 * boolean isMiTWS(String)                    boolean checkIsMiTWS(String)
 * String  isSupportAudioSwitch(String)       void    changeAncMode(int, BluetoothDevice)
 * void    changeAncLevel(String, BluetoothDevice)  void registerCallbackDevice(callback, device)
 * ```
 *
 * So the Binder class is discovered at runtime from `Service.onBind`, and its methods are hooked
 * **by name**. That keeps the hook stable across releases even though the class name is not.
 *
 * The reply format follows the reference implementation on the same platform:
 * `<8 hex digit device id>,<24 character capability bitmap>`.
 */
class XiaomiHeadsetSpoofHook : SafeHookContext() {

    override val tag: String get() = "HeadsetSpoof"

    /** Service instance, which is also a Context; needed to reach our own app process. */
    @Volatile
    private var serviceContext: Context? = null

    private val hookedBinderClasses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Addresses that passed the name check. Learned at runtime because the hook cannot know the
     * bonded XM5's address ahead of time, and the string-arg overloads only carry an address.
     */
    private val targetAddresses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Client callbacks registered by the settings UI / Fusion Device Center. */
    private val callbacks = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()

    @Volatile
    private var pushHandler: android.os.Handler? = null

    override fun onHook() {
        install("BluetoothHeadsetService#onCreate") {
            val method = findMethodAnywhere(SERVICE, "onCreate")
            hookAfter(method) {
                serviceContext = instance as? Context
                HookLog.i(tag, "service created, context=${serviceContext != null}")
            }
        }

        install("BluetoothHeadsetService#onBind") {
            val method = findMethodAnywhere(SERVICE, "onBind", Intent::class.java)
            hookAfter(method) {
                serviceContext = serviceContext ?: (instance as? Context)
                val binder = result as? IBinder ?: return@hookAfter
                installBinderHooks(binder.javaClass)
            }
        }

        HookLog.i(tag, "Xiaomi headset spoof installed in $packageName")
    }

    // ---------------------------------------------------------------- binder hooks

    private fun installBinderHooks(binderClass: Class<*>) {
        val className = binderClass.name
        if (!hookedBinderClasses.add(className)) return

        HookLog.i(tag, "binder class $className descriptor=${runCatching { binderClass.name }.getOrNull()}")

        pushHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // What is actually offered: if these names survive obfuscation we can hook them directly.
        binderClass.declaredMethods
            .map { it.name }
            .filter { it.length > 2 && !it.startsWith("access\$") && !it.contains("\$\$Nest\$") }
            .sorted()
            .chunked(8)
            .forEach { HookLog.i(tag, "  methods: ${it.joinToString(" ")}") }

        dumpMiuiAncConstants()

        hookStringDeviceResult(binderClass, "checkSupport") { FAKE_SUPPORT }
        hookAddressStringResult(binderClass, "getDeviceInfo") { FAKE_SUPPORT }
        hookAddressStringResult(binderClass, "isSupportAudioSwitch") { "1" }
        hookAddressBooleanResult(binderClass, "isMiTWS", true)
        hookAddressBooleanResult(binderClass, "checkIsMiTWS", true)
        hookAddressBooleanResult(binderClass, "getRingFindState", false)

        hookAncMode(binderClass)
        hookAncLevel(binderClass)
        hookCallbackRegistration(binderClass)
        startStateWatcher()
    }

    /**
     * Push the status whenever it changes, rather than only in response to a call.
     *
     * HyperOS does not re-read the headset on its own, so a change made anywhere - the module UI,
     * the Fusion Device Center, or the earbuds' own buttons - would otherwise leave every open page
     * showing stale values.
     */
    private fun startStateWatcher() {
        Thread({
            var lastPayload: String? = null
            var lastPresence: String? = null
            var lastIslandRequest = -1
            while (true) {
                runCatching {
                    HeadsetStateCache.refresh()
                    val bundle = HeadsetStateCache.get() ?: return@runCatching
                    val payload = bundle.getString(KEY_PAYLOAD)
                    val islandRequest = bundle.getInt(KEY_ISLAND_REQUEST, 0)
                    val explicitRequest = lastIslandRequest >= 0 && islandRequest != lastIslandRequest
                    lastIslandRequest = islandRequest

                    if (payload != null && payload != lastPayload) {
                        lastPayload = payload
                        if (callbacks.isNotEmpty()) pushStatus(null)
                    }

                    // The popup is an announcement, not a readout: raise it when a bud appears or
                    // disappears, plus whenever something explicitly asks for it.
                    val presence = presenceOf(bundle)
                    if (presence != lastPresence || explicitRequest) {
                        lastPresence = presence
                        if (bundle.getBoolean(KEY_ISLAND_ENABLED, false)) showStrongToast(bundle)
                    }
                }
                runCatching { Thread.sleep(STATE_WATCH_MS) }
            }
        }, "xmsound-headset-watch").apply { isDaemon = true }.start()
    }

    private fun presenceOf(bundle: android.os.Bundle): String =
        "${bundle.getInt(KEY_LEFT, -1) >= 0}/${bundle.getInt(KEY_RIGHT, -1) >= 0}"

    /**
     * Raise the native connection popup plus its island form.
     *
     * Runs here because only this process holds the `STATUS_BAR` permission the call needs, and
     * because the earphone animations are read from this package's own resources.
     */
    private fun showStrongToast(bundle: android.os.Bundle) {
        val context = serviceContext ?: return
        val left = bundle.getInt(KEY_LEFT, -1).takeIf { it in 0..100 }
        val right = bundle.getInt(KEY_RIGHT, -1).takeIf { it in 0..100 }
        if (left == null && right == null) return

        MiuiStrongToast.showBattery(
            context = context,
            leftLevel = left,
            leftCharging = bundle.getBoolean(KEY_LEFT_CHARGING, false),
            rightLevel = right,
            rightCharging = bundle.getBoolean(KEY_RIGHT_CHARGING, false),
        )
    }

    /**
     * HyperOS pushes headset status through `IMiuiHeadsetCallback.refreshStatus`.
     *
     * The real service is swallowed (`result = null`) so its "not supported" answer can never
     * overwrite the values we push, and the callback is kept so the module can send its own status
     * for every connected device it cares about.
     */
    private fun hookCallbackRegistration(binderClass: Class<*>) {
        val callbackClass = findClassOrNull(CALLBACK) ?: run {
            HookLog.w(tag, "MISSING $CALLBACK")
            return
        }

        install("register") {
            val method = findMethodAnywhere(binderClass.name, "register", callbackClass)
            hookBefore(method) {
                val callback = args.getOrNull(0)
                if (callback != null) {
                    callbacks.add(callback)
                    result = null
                    HookLog.i(tag, "swallowed register, callback=$callback")
                    pushStatusToAll()
                }
            }
        }

        install("registerCallbackDevice") {
            val method = findMethodAnywhere(
                binderClass.name,
                "registerCallbackDevice",
                callbackClass,
                BluetoothDevice::class.java,
            )
            hookBefore(method) {
                val callback = args.getOrNull(0)
                val device = args.getOrNull(1) as? BluetoothDevice
                if (callback != null && isTargetDevice(device)) {
                    callbacks.add(callback)
                    result = null
                    val address = device?.address
                    HookLog.i(tag, "swallowed registerCallbackDevice for $address")
                    // Push straight away and again shortly after: the client has not finished
                    // installing its own listeners at the moment of registration, so a single
                    // immediate push can be dropped.
                    pushStatus(address)
                    schedulePush(address, 400L)
                    schedulePush(address, 1_500L)
                }
            }
        }

        install("unregister") {
            val method = findMethodAnywhere(
                binderClass.name,
                "unregister",
                callbackClass,
                BluetoothDevice::class.java,
            )
            hookBefore(method) {
                val callback = args.getOrNull(0)
                if (callback != null) {
                    callbacks.remove(callback)
                    HookLog.i(tag, "forgot callback on unregister")
                }
            }
        }
    }

    // ---------------------------------------------------------------- status push

    private fun pushStatusToAll() {
        targetAddresses.toList().forEach { pushStatus(it) }
    }

    private fun schedulePush(address: String?, delayMs: Long) {
        val handler = pushHandler ?: return
        // Pull fresh state off the calling thread, then hand it to the UI shortly after.
        HeadsetStateCache.refreshAsync()
        handler.postDelayed({ pushStatus(address) }, delayMs)
    }

    /**
     * Ask our own process for the current state and hand it to every registered callback.
     *
     * The heavyweight part (the SPP session) lives in the module's process; here we only relay.
     */
    private fun pushStatus(address: String?) {
        val target = address ?: targetAddresses.firstOrNull() ?: return
        if (callbacks.isEmpty()) return

        val bundle = queryHeadsetState() ?: run {
            HookLog.w(tag, "no state snapshot yet")
            return
        }

        val payload = bundle.getString(KEY_PAYLOAD) ?: return
        val connected = bundle.getBoolean(KEY_CONNECTED, false)
        val keys = listOf(target, FAKE_DEVICE_ID, target.uppercase()).distinct()
        HookLog.i(tag, "pushing status keys=$keys connected=$connected payload=$payload")

        callbacks.toList().forEach { callback ->
            keys.forEach { key ->
                runCatching { callMethod(callback, "refreshStatus", key, payload) }
                    .onFailure {
                        HookLog.w(tag, "refreshStatus($key) failed: ${it.message}")
                        callbacks.remove(callback)
                    }
            }
        }
    }

    /** Enum-ish int constants MiLink uses for ANC; needed to translate changeAncMode's argument. */
    private fun dumpMiuiAncConstants() {
        listOf(ANC_MODE, ANC_STATE).forEach { name ->
            val cls = findClassOrNull(name) ?: run {
                HookLog.w(tag, "MISSING $name")
                return@forEach
            }
            cls.declaredFields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                .sortedBy { it.name }
                .forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        HookLog.i(tag, "CONST ${cls.simpleName}.${f.name} = ${f.getInt(null)}")
                    }
                }
        }
    }

    // ---------------------------------------------------------------- spoofing helpers

    private fun hookStringDeviceResult(binderClass: Class<*>, name: String, value: () -> String) {
        install("$name(BluetoothDevice)") {
            val method = findMethodAnywhere(binderClass.name, name, BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.getOrNull(0) as? BluetoothDevice
                if (!isTargetDevice(device)) return@hookBefore
                val forced = value()
                result = forced
                HookLog.i(tag, "$name(${device?.address}) forced -> $forced")
            }
        }
    }

    /**
     * Only the WF-1000XM5 may be spoofed. Without this the recorder and any other bonded device
     * would also be advertised as a supported Xiaomi headset.
     */
    private fun isTargetDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = device.address.orEmpty()
        if (address.isNotEmpty() && targetAddresses.contains(address.uppercase())) return true

        val name = runCatching { device.name }.getOrNull().orEmpty()
        val matched = TARGET_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
        if (matched && address.isNotEmpty()) targetAddresses.add(address.uppercase())
        return matched
    }

    private fun hookAddressStringResult(binderClass: Class<*>, name: String, value: () -> String) {
        install("$name(String)") {
            val method = findMethodAnywhere(binderClass.name, name, String::class.java)
            hookBefore(method) {
                val address = args.getOrNull(0) as? String
                // Some callers pass the address, others the device id we advertise.
                if (!isTargetAddress(address) && address != FAKE_DEVICE_ID) return@hookBefore
                val forced = value()
                result = forced
                HookLog.i(tag, "$name($address) forced -> $forced")
            }
        }
    }

    private fun isTargetAddress(address: String?): Boolean =
        !address.isNullOrEmpty() && targetAddresses.contains(address.uppercase())

    private fun hookAddressBooleanResult(binderClass: Class<*>, name: String, value: Boolean) {
        install("$name(String)->$value") {
            val method = runCatching { findMethodAnywhere(binderClass.name, name, String::class.java) }
                .getOrElse { findMethodAnywhere(binderClass.name, name, BluetoothDevice::class.java) }
            hookBefore(method) {
                val arg = args.getOrNull(0)
                if (arg is String && !isTargetAddress(arg) && arg != FAKE_DEVICE_ID) return@hookBefore
                if (arg is BluetoothDevice && !isTargetDevice(arg)) return@hookBefore
                result = value
                logOnChange("$name($arg)", value)
            }
        }
    }

    // ---------------------------------------------------------------- noise control

    /**
     * `changeAncMode(int, BluetoothDevice)` - the settings UI's noise-control switch.
     * The int is translated with the values dumped from `com.miui.headset.api.AncMode`.
     */
    private fun hookAncMode(binderClass: Class<*>) {
        install("changeAncMode") {
            val method = findMethodAnywhere(
                binderClass.name,
                "changeAncMode",
                Int::class.javaPrimitiveType!!,
                BluetoothDevice::class.java,
            )
            hookBefore(method) {
                val mode = args.getOrNull(0) as? Int
                val device = args.getOrNull(1) as? BluetoothDevice
                if (!isTargetDevice(device)) return@hookBefore
                HookLog.i(tag, "changeAncMode mode=$mode device=${device?.address} -> forwarding")
                forwardNoiseMode(mode)
                // Reflect the new state back to the UI once the session has applied it.
                serviceContext?.let { ctx ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { pushStatus(device?.address) },
                        PUSH_AFTER_COMMAND_MS,
                    )
                }
            }
        }
    }

    /** `changeAncLevel(String, BluetoothDevice)` - the ambient-sound level slider. */
    private fun hookAncLevel(binderClass: Class<*>) {
        install("changeAncLevel") {
            val method = findMethodAnywhere(
                binderClass.name,
                "changeAncLevel",
                String::class.java,
                BluetoothDevice::class.java,
            )
            hookBefore(method) {
                val level = args.getOrNull(0) as? String
                val device = args.getOrNull(1) as? BluetoothDevice
                if (!isTargetDevice(device)) return@hookBefore
                HookLog.i(tag, "changeAncLevel level=$level device=${device?.address} -> forwarding")
                level?.toIntOrNull()?.let { forwardAmbientLevel(it) }
                serviceContext?.let { ctx ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { pushStatus(device?.address) },
                        PUSH_AFTER_COMMAND_MS,
                    )
                }
            }
        }
    }

    private fun forwardNoiseMode(mode: Int?) {
        if (mode == null) return
        val name = when (mode) {
            ANC_MODE_NOISE_CANCELLING -> "nc"
            ANC_MODE_AMBIENT -> "ambient"
            ANC_MODE_OFF -> "off"
            else -> null
        }
        if (name == null) {
            HookLog.w(tag, "unknown ANC mode $mode, not forwarding")
            return
        }
        sendToApp(ACTION_SET_NOISE) { it.putExtra("mode", name) }
    }

    private fun forwardAmbientLevel(level: Int) {
        sendToApp(ACTION_SET_AMBIENT_LEVEL) { it.putExtra("level", level) }
    }

    /**
     * The Sony session runs in the module's own process, so control changes are handed over as an
     * explicit broadcast to that package.
     */
    private fun sendToApp(action: String, configure: (Intent) -> Unit) {
        val context = serviceContext ?: run {
            HookLog.w(tag, "no context yet, cannot forward $action")
            return
        }
        runCatching {
            val intent = Intent(action).setPackage(MODULE_PACKAGE)
            configure(intent)
            context.sendBroadcast(intent)
            HookLog.i(tag, "forwarded $action")
        }.onFailure { HookLog.w(tag, "forward $action failed: ${it.message}") }
    }

    private companion object {
        const val SERVICE = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
        const val MODULE_PACKAGE = "moe.yanhe.xmsound"

        const val ACTION_SET_NOISE = "moe.yanhe.xmsound.action.SET_NOISE"
        const val ACTION_SET_AMBIENT_LEVEL = "moe.yanhe.xmsound.action.SET_AMBIENT_LEVEL"

        /** Reference implementation's device id and capability bitmap for the same platform. */
        const val FAKE_DEVICE_ID = "01010607"
        const val FAKE_SUPPORT = "$FAKE_DEVICE_ID,000000000000000010000000"

        /** Only these models are spoofed; everything else keeps the stock AOSP device page. */
        val TARGET_NAME_PREFIXES = listOf("WF-1000XM5", "WH-1000XM5", "WF-1000XM4")

        const val CALLBACK = "com.android.bluetooth.ble.app.IMiuiHeadsetCallback"

        const val STATE_AUTHORITY = "moe.yanhe.xmsound.state"
        const val METHOD_STATE = "state"
        const val KEY_PAYLOAD = "payload"
        const val KEY_CONNECTED = "connected"
        const val KEY_ISLAND_ENABLED = "islandEnabled"
        const val KEY_ISLAND_REQUEST = "islandRequest"
        const val KEY_LEFT = "left"
        const val KEY_RIGHT = "right"
        const val KEY_LEFT_CHARGING = "leftCharging"
        const val KEY_RIGHT_CHARGING = "rightCharging"

        /** Grace period for the SPP session to apply a command before the UI is refreshed. */
        const val PUSH_AFTER_COMMAND_MS = 900L

        /** How often the status watcher looks for a change worth pushing to the UI. */
        const val STATE_WATCH_MS = 1_000L

        const val ANC_MODE = "com.miui.headset.api.AncMode"
        const val ANC_STATE = "com.miui.headset.api.AncState"

        /** Values observed in `com.miui.headset.api.AncMode`; only used for forwarding. */
        const val ANC_MODE_OFF = 0
        const val ANC_MODE_NOISE_CANCELLING = 1
        const val ANC_MODE_AMBIENT = 2
    }
}
