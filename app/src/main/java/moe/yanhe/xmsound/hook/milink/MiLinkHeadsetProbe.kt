package moe.yanhe.xmsound.hook.milink

import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.hook.SafeHookContext

/**
 * Read-only probe of HyperOS' headset data model in `com.milink.service`.
 *
 * The Fusion Device Center (融合设备中心) and the Bluetooth settings card read the headset through
 * these classes:
 *
 *  - `com.miui.headset.api.HeadsetInfo`  - `getAddress/getDeviceId/getName/getPowers/getMode/...`
 *  - `com.miui.circulate.world.headset.data.HeadsetState` - `getName/getBattery/getBluetoothMode`
 *
 * This hook changes nothing. It reports what HyperOS currently believes about the connected
 * headset, which is the input needed to decide where the WF-1000XM5's real battery and noise mode
 * have to be injected.
 */
class MiLinkHeadsetProbe : SafeHookContext() {

    override val tag: String get() = "MiLink"

    override fun onHook() {
        HeadsetInfoFields.forEach { (className, getter) ->
            hookGetter(className, getter) { value ->
                logOnChange("HeadsetInfo.$getter [${packageName}]", describe(value))
            }
        }

        HeadsetStateFields.forEach { getter ->
            hookGetter(HEADSET_STATE, getter) { value ->
                logOnChange("HeadsetState.$getter [${packageName}]", describe(value))
            }
        }

        HookLog.i(tag, "MiLink headset probe installed in $packageName")
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { describe(it) }
        else -> "${value.javaClass.simpleName}($value)"
    }

    private companion object {
        const val HEADSET_INFO = "com.miui.headset.api.HeadsetInfo"
        const val HEADSET_STATE = "com.miui.circulate.world.headset.data.HeadsetState"

        /** Getter name -> nothing; kept as a pair list so a missing one is reported individually. */
        val HeadsetInfoFields = listOf(
            HEADSET_INFO to "getName",
            HEADSET_INFO to "getAddress",
            HEADSET_INFO to "getDeviceId",
            HEADSET_INFO to "getPowers",
            HEADSET_INFO to "getMode",
            HEADSET_INFO to "getSwitchState",
            HEADSET_INFO to "getType",
            HEADSET_INFO to "getAudioEffectState",
        )

        val HeadsetStateFields = listOf(
            "getName",
            "getBattery",
            "getBluetoothMode",
            "getLowDelayMode",
        )
    }
}
