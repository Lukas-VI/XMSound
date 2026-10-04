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
        /**
         * Classes dumped in full, ignoring [NAME_FILTER]. Used for the primary integration target,
         * where knowing every member matters more than keeping the log short.
         */
        private val FULL_DUMP = setOf(
            "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService",
            "com.android.bluetooth.ble.app.MiuiHeadsetIslandParam",
            "com.android.bluetooth.ble.app.IMiuiHeadsetService",
            "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub",
            "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy",
            "com.miui.headset.runtime.AncBatteryController",
        )

        val TARGETS: Map<String, List<String>> = mapOf(
            "com.android.bluetooth" to listOf(
                "com.android.bluetooth.btservice.HyperAdapterService",
                "com.android.bluetooth.btservice.HyperAdapterService\$DeviceInfo",
                "com.android.bluetooth.btservice.HyperAdapterService\$MiAbstractionLayer",
                "com.android.bluetooth.hfp.HyperHeadsetService",
                "com.android.bluetooth.bas.BatteryService",
                "com.android.bluetooth.a2dp.A2dpService",
                // Also probed here: BluetoothExtension.apk declares this package, and it is not yet
                // known which process ends up loading it.
                "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService",
                "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub",
                "com.android.bluetooth.ble.app.MiuiHeadsetInfo",
            ),
            // The Xiaomi headset Binder lives in BluetoothExtension.apk but declares the
            // com.android.bluetooth.ble.app package, so it is loaded into the Bluetooth process.
            "com.xiaomi.bluetooth" to listOf(
                "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService",
                "com.android.bluetooth.ble.app.headset.plugin.BluetoothHeadsetServicePlugin",
                "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub",
                "com.android.bluetooth.ble.app.IMiuiHeadsetServicePlugin\$Stub",
                "com.android.bluetooth.ble.app.MiuiHeadsetInfo",
                "com.android.bluetooth.ble.app.MiuiHeadsetInfoV2",
                "com.android.bluetooth.ble.app.MiuiHeadsetIslandParam",
                "com.android.bluetooth.ble.app.MiuiHeadsetNotification",
            ),
            "com.milink.service" to listOf(
                "com.miui.headset.runtime.AncBatteryController",
                "com.miui.headset.runtime.AncBatteryModel",
                "com.miui.headset.api.HeadsetInfo",
                "com.miui.headset.api.AncState",
                "com.miui.headset.api.AncMode",
                "com.miui.circulate.api.protocol.headset.HeadsetServiceClient",
                "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
                // The AIDL client for the Xiaomi headset Binder. Its method names name the
                // operations, and each one funnels into BinderProxy.transact(code, ...).
                "com.android.bluetooth.ble.app.IMiuiHeadsetService",
                "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub",
                "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy",
                "com.android.bluetooth.ble.app.IMiuiHeadsetCallback\$Stub\$Proxy",
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

        val methods = cls.declaredMethods
            .filter { FULL_DUMP.contains(className) || matches(it.name) }
            .sortedBy { it.name }
        val fields = cls.declaredFields
            .filter { FULL_DUMP.contains(className) || matches(it.name) }
            .sortedBy { it.name }

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
        fields.forEach { f ->
            // AIDL puts the opcodes in static final int fields (TRANSACTION_*). javac inlines
            // uses of compile-time constants, so reading them back through reflection is the only
            // way to learn the codes this ROM actually uses.
            val value = runCatching {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                    f.type == Int::class.javaPrimitiveType
                ) {
                    f.isAccessible = true
                    " = %d (0x%02X)".format(f.getInt(null), f.getInt(null))
                } else {
                    ""
                }
            }.getOrDefault("")
            HookLog.i(TAG, "  F ${f.type.simpleName} ${f.name}$value")
        }
    }

    private fun matches(name: String): Boolean {
        val lower = name.lowercase()
        return NAME_FILTER.any { lower.contains(it) }
    }
}
