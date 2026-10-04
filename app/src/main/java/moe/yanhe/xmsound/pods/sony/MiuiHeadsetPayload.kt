package moe.yanhe.xmsound.pods.sony

/**
 * Builds the status string HyperOS' headset UI expects from
 * `IMiuiHeadsetCallback.refreshStatus(deviceKey, payload)`.
 *
 * The format is a 16 field comma separated list. Slots that this headset has no notion of are left
 * empty, exactly like the reference implementation on the same platform:
 *
 * ```
 * 0 left battery      1 right battery     2 case battery      3 (empty)
 * 4 (empty)           5 (empty)           6 (empty)           7 noise mode
 * 8 "true"            9 (empty)          10 (empty)          11 "00"
 * 12 (empty)         13 "00"             14 "00"             15 (empty)
 * ```
 *
 * Battery encoding: `255` when that part is absent, otherwise the level 0-100, with bit 7 set while
 * charging.
 *
 * Noise codes (`SonyNoiseMode`): MIUI numbers its modes rather than ordering them, and the WF-1000XM5
 * only has the three plain ones - it has no Adaptive/Light/Medium/Deep steps, so the "medium"
 * noise-cancelling code is used for plain NC.
 */
object MiuiHeadsetPayload {

    const val BATTERY_ABSENT = 255

    const val ANC_OFF = "0000"
    const val ANC_NOISE_CANCELLING = "0100"
    const val ANC_AMBIENT = "0200"
    const val ANC_AMBIENT_VOICE = "0201"

    private const val SLOT_COUNT = 16

    fun build(
        left: SonyBudBattery?,
        right: SonyBudBattery?,
        case: SonyBudBattery?,
        mode: SonyNoiseMode,
        focusOnVoice: Boolean,
    ): String {
        val values = MutableList(SLOT_COUNT) { "" }
        values[0] = battery(left)
        values[1] = battery(right)
        values[2] = battery(case)
        values[7] = noiseCode(mode, focusOnVoice)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    fun build(state: SonyHeadsetState): String = build(
        left = state.battery.left,
        right = state.battery.right,
        case = state.battery.case,
        mode = state.noise.mode,
        focusOnVoice = state.noise.focusOnVoice,
    )

    /** `255` = absent, otherwise the level, with bit 7 set while charging. */
    fun battery(bud: SonyBudBattery?): String {
        if (bud == null) return BATTERY_ABSENT.toString()
        val level = bud.level.coerceIn(0, 100)
        return (if (bud.charging) level or 128 else level).toString()
    }

    fun noiseCode(mode: SonyNoiseMode, focusOnVoice: Boolean): String = when (mode) {
        SonyNoiseMode.OFF -> ANC_OFF
        SonyNoiseMode.NOISE_CANCELLING -> ANC_NOISE_CANCELLING
        SonyNoiseMode.AMBIENT_SOUND -> if (focusOnVoice) ANC_AMBIENT_VOICE else ANC_AMBIENT
        SonyNoiseMode.UNKNOWN -> ANC_OFF
    }

    /**
     * Inverse of [noiseCode] for the modes this headset supports, used to translate a UI tap into a
     * protocol request. Returns null for codes with no WF-1000XM5 equivalent.
     */
    fun modeForNoiseCode(code: String?): SonyNoiseMode? = when (code) {
        ANC_OFF -> SonyNoiseMode.OFF
        ANC_NOISE_CANCELLING -> SonyNoiseMode.NOISE_CANCELLING
        ANC_AMBIENT, ANC_AMBIENT_VOICE -> SonyNoiseMode.AMBIENT_SOUND
        else -> null
    }
}
