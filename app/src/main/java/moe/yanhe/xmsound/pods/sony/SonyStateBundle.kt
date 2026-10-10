package moe.yanhe.xmsound.pods.sony

import android.os.Bundle

/**
 * Wire format for the headset state, shared by every process that takes part.
 *
 * The session lives in the Bluetooth process and the UI lives in the module process, so the state
 * crosses a process boundary. A [Bundle] is used rather than a `Parcelable` because the same payload
 * also has to be readable by the spoofing hooks, which only need a handful of scalars.
 *
 * Keys are the ones the hooks already used, so the spoof layer needed no changes when the session
 * moved out of the module process.
 */
object SonyStateBundle {

    const val KEY_PAYLOAD = "payload"
    const val KEY_ADDRESS = "address"
    const val KEY_CONNECTED = "connected"
    const val KEY_MODEL = "model"
    const val KEY_LEFT = "left"
    const val KEY_RIGHT = "right"
    const val KEY_CASE = "case"
    const val KEY_LEFT_CHARGING = "leftCharging"
    const val KEY_RIGHT_CHARGING = "rightCharging"
    const val KEY_CASE_CHARGING = "caseCharging"
    const val KEY_ANC_STATE = "ancState"
    const val KEY_AMBIENT_LEVEL = "ambientLevel"
    const val KEY_ISLAND_ENABLED = "islandEnabled"
    const val KEY_ISLAND_REQUEST = "islandRequest"

    /** Extra fields the module UI needs to render the details page. */
    const val KEY_FIRMWARE = "firmware"
    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_NOISE_MODE = "noiseMode"
    const val KEY_FOCUS_ON_VOICE = "focusOnVoice"
    const val KEY_WIND_NOISE_REDUCTION = "windNoiseReduction"
    const val KEY_DEVICE_NAME = "deviceName"
    const val KEY_ERROR = "error"

    /** The model this module presents to HyperOS. */
    const val MODEL = "WF-1000XM5"

    /** 0 off, 1 noise cancelling, 2 ambient - the numbering HyperOS' API uses. */
    fun ancState(mode: SonyNoiseMode): Int = when (mode) {
        SonyNoiseMode.OFF -> 0
        SonyNoiseMode.NOISE_CANCELLING -> 1
        SonyNoiseMode.AMBIENT_SOUND -> 2
        SonyNoiseMode.UNKNOWN -> 0
    }

    fun modeForAncState(value: Int): SonyNoiseMode = when (value) {
        0 -> SonyNoiseMode.OFF
        1 -> SonyNoiseMode.NOISE_CANCELLING
        2 -> SonyNoiseMode.AMBIENT_SOUND
        else -> SonyNoiseMode.UNKNOWN
    }

    fun encode(
        state: SonyHeadsetState,
        address: String?,
        deviceName: String?,
        islandEnabled: Boolean,
        islandRequest: Int,
        error: String? = null,
    ): Bundle = Bundle().apply {
        putString(KEY_PAYLOAD, MiuiHeadsetPayload.build(state))
        putString(KEY_ADDRESS, address)
        putString(KEY_DEVICE_NAME, deviceName)
        putString(KEY_MODEL, MODEL)
        putString(KEY_ERROR, error)

        putBoolean(KEY_CONNECTED, state.connected)
        putString(KEY_FIRMWARE, state.firmware)
        putInt(KEY_PROTOCOL_VERSION, state.protocolVersion)

        putInt(KEY_LEFT, state.battery.left?.level ?: -1)
        putInt(KEY_RIGHT, state.battery.right?.level ?: -1)
        putInt(KEY_CASE, state.battery.case?.level ?: -1)
        putBoolean(KEY_LEFT_CHARGING, state.battery.left?.charging == true)
        putBoolean(KEY_RIGHT_CHARGING, state.battery.right?.charging == true)
        putBoolean(KEY_CASE_CHARGING, state.battery.case?.charging == true)

        putInt(KEY_ANC_STATE, ancState(state.noise.mode))
        putInt(KEY_AMBIENT_LEVEL, state.noise.ambientLevel)
        putString(KEY_NOISE_MODE, state.noise.mode.name)
        putBoolean(KEY_FOCUS_ON_VOICE, state.noise.focusOnVoice)
        putBoolean(KEY_WIND_NOISE_REDUCTION, state.noise.windNoiseReduction)

        putBoolean(KEY_ISLAND_ENABLED, islandEnabled)
        putInt(KEY_ISLAND_REQUEST, islandRequest)
    }

    /** Rebuild the state from a bundle produced by [encode]. */
    fun decode(bundle: Bundle): SonyHeadsetState {
        fun bud(levelKey: String, chargingKey: String): SonyBudBattery? {
            val level = bundle.getInt(levelKey, -1)
            if (level < 0) return null
            return SonyBudBattery(level, bundle.getBoolean(chargingKey, false))
        }

        val mode = bundle.getString(KEY_NOISE_MODE)
            ?.let { name -> SonyNoiseMode.entries.firstOrNull { it.name == name } }
            ?: modeForAncState(bundle.getInt(KEY_ANC_STATE, 0))

        return SonyHeadsetState(
            connected = bundle.getBoolean(KEY_CONNECTED, false),
            protocolVersion = bundle.getInt(KEY_PROTOCOL_VERSION, 0),
            firmware = bundle.getString(KEY_FIRMWARE),
            battery = SonyBatteryState(
                left = bud(KEY_LEFT, KEY_LEFT_CHARGING),
                right = bud(KEY_RIGHT, KEY_RIGHT_CHARGING),
                case = bud(KEY_CASE, KEY_CASE_CHARGING),
            ),
            noise = SonyNoiseState(
                mode = mode,
                ambientLevel = bundle.getInt(KEY_AMBIENT_LEVEL, 10),
                focusOnVoice = bundle.getBoolean(KEY_FOCUS_ON_VOICE, false),
                windNoiseReduction = bundle.getBoolean(KEY_WIND_NOISE_REDUCTION, false),
            ),
        )
    }
}
