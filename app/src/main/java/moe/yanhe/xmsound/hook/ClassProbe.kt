package moe.yanhe.xmsound.hook

import android.app.Application

/**
 * One-shot reconnaissance hook.
 *
 * HyperOS renames and re-layers the AOSP Bluetooth stack, and its headset UI lives in private
 * `com.miui.headset.*` / `com.miui.circulate.*` classes that no public documentation covers.
 * Rather than guess hook targets from a decompiler or from another project's notes, this dumps the
 * **live** API surface of the classes we care about into logcat:
 *
 * ```
 * adb logcat -s XMSound-Hook:*
 * ```
 *
 * It is read-only: it hooks nothing and changes no state. Turn it off via
 * [HookEntry.PROBE_ENABLED] once the targets for the current ROM are known.
 */
class ClassProbe : HookContext() {

    companion object {
        private const val TAG = "Probe"

        /** Only members whose name contains one of these are printed, to keep the output usable. */
        private val NAME_FILTER = listOf(
            "anc", "noise", "battery", "power", "level", "charge",
            "headset", "earbud", "bud", "tws", "device", "name", "address",
            "connect", "state", "status", "support", "capab", "config", "info",
            "hyper", "miui", "mode", "spatial", "wear", "firmware", "version",
            "type", "update", "notify", "listener", "callback", "query", "profile",
        )

        /**
         * Classes worth dumping, per scoped package. Names come from the ROM's own APKs
         * (`/apex/com.android.bt/.../Bluetooth.apk`, `MiLinkOS3Cn.apk`, `BluetoothExtension.apk`),
         * so anything reported MISSING has genuinely moved in this release - which is itself the
         * signal we need.
         */
        val TARGETS: Map<String, List<String>> = mapOf(
            "com.android.bluetooth" to listOf(
                "com.android.bluetooth.btservice.HyperAdapterService",
                "com.android.bluetooth.btservice.HyperAdapterService\$DeviceInfo",
                "com.android.bluetooth.btservice.HyperAdapterService\$MiAbstractionLayer",
                "com.android.bluetooth.hfp.HyperHeadsetService",
                "com.android.bluetooth.hfp.HyperHeadsetStateMachine",
                "com.android.bluetooth.bas.BatteryService",
                "com.android.bluetooth.bas.BatteryStateMachine",
                "com.android.bluetooth.a2dp.A2dpService",
            ),
            "com.milink.service" to listOf(
                "com.miui.headset.api.HeadsetInfo",
                "com.miui.headset.api.AncState",
                "com.miui.headset.api.AncMode",
                "com.miui.headset.api.AudioEffectState",
                "com.miui.headset.api.HeadsetType",
                "com.miui.headset.api.HeadsetClient",
                "com.miui.headset.api.HeadsetHost",
                "com.miui.headset.api.HeadsetResult",
                "com.miui.headset.api.IHeadsetLocalService\$Stub",
                "com.miui.circulate.world.headset.HeadsetContentManager",
                "com.miui.circulate.world.headset.data.HeadsetState",
                "com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo",
                "com.miui.circulate.api.protocol.headset.HeadsetServiceClient",
                "com.miui.circulate.api.service.CirculateServiceInfo",
            ),
            "com.xiaomi.bluetooth" to listOf(
                "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
                "com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi",
                "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService",
                "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub",
            ),
        )
    }

    override fun onHook() {
        val targets = TARGETS[packageName].orEmpty()
        HookLog.i(TAG, "probing ${targets.size} classes in $packageName process=${processName()}")
        targets.forEach { dump(it) }
        HookLog.i(TAG, "probe done for $packageName")
    }

    private fun processName(): String =
        runCatching { Application.getProcessName() }.getOrNull() ?: "?"

    private fun dump(className: String) {
        val cls = findClassOrNull(className)
        if (cls == null) {
            HookLog.w(TAG, "MISSING $className")
            return
        }

        val methods = cls.declaredMethods.filter { matches(it.name) }.sortedBy { it.name }
        val fields = cls.declaredFields.filter { matches(it.name) }.sortedBy { it.name }

        HookLog.i(
            TAG,
            "CLASS $className  (${cls.declaredMethods.size} methods, ${cls.declaredFields.size} fields)",
        )
        HookLog.i(TAG, "  extends ${cls.superclass?.name}  implements ${cls.interfaces.joinToString { it.simpleName }}")

        methods.forEach { m ->
            HookLog.i(
                TAG,
                "  M ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})",
            )
        }
        fields.forEach { f -> HookLog.i(TAG, "  F ${f.type.simpleName} ${f.name}") }
    }

    private fun matches(name: String): Boolean {
        val lower = name.lowercase()
        return NAME_FILTER.any { lower.contains(it) }
    }
}
