package moe.yanhe.xmsound.hook

/**
 * One-shot reconnaissance hook.
 *
 * HyperOS renames and re-layers the AOSP Bluetooth stack (this ROM adds `HyperAdapterService`,
 * `HyperHeadsetService` and `HyperHeadsetStateMachine` on top of the AOSP classes), and those
 * internals change between HyperOS releases. Rather than guess hook targets from a decompiler or
 * from another project's notes, this dumps the **live** API surface of the classes we care about
 * into logcat:
 *
 * ```
 * adb logcat -s XMSound-Hook:*
 * ```
 *
 * It is read-only: it hooks nothing and changes no state. Turn it off by clearing [PROBE_ENABLED]
 * once the targets for the current ROM are known.
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
        )

        /** Classes whose API surface is worth dumping on this ROM. */
        val TARGETS = listOf(
            "com.android.bluetooth.btservice.HyperAdapterService",
            "com.android.bluetooth.btservice.HyperAdapterService\$DeviceInfo",
            "com.android.bluetooth.btservice.HyperAdapterService\$MiAbstractionLayer",
            "com.android.bluetooth.btservice.AdapterService",
            "com.android.bluetooth.hfp.HyperHeadsetService",
            "com.android.bluetooth.hfp.HyperHeadsetStateMachine",
            "com.android.bluetooth.hfp.HeadsetService",
            "com.android.bluetooth.a2dp.A2dpService",
            "com.android.bluetooth.bas.BatteryService",
        )
    }

    override fun onHook() {
        HookLog.i(TAG, "probing ${TARGETS.size} classes in ${packageName}")
        TARGETS.forEach { dump(it) }
        HookLog.i(TAG, "probe done")
    }

    private fun dump(className: String) {
        val cls = findClassOrNull(className)
        if (cls == null) {
            HookLog.w(TAG, "MISSING $className")
            return
        }

        val methods = cls.declaredMethods
            .filter { matches(it.name) }
            .sortedBy { it.name }
        val fields = cls.declaredFields.filter { matches(it.name) }.sortedBy { it.name }

        HookLog.i(TAG, "CLASS $className  (${cls.declaredMethods.size} methods, ${cls.declaredFields.size} fields)")
        HookLog.i(TAG, "  extends ${cls.superclass?.name}  implements ${cls.interfaces.joinToString { it.simpleName }}")

        methods.forEach { m ->
            HookLog.i(
                TAG,
                "  M ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})",
            )
        }
        fields.forEach { f ->
            HookLog.i(TAG, "  F ${f.type.simpleName} ${f.name}")
        }
    }

    private fun matches(name: String): Boolean {
        val lower = name.lowercase()
        return NAME_FILTER.any { lower.contains(it) }
    }
}
