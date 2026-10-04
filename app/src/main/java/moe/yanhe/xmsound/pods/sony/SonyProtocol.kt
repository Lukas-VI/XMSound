package moe.yanhe.xmsound.pods.sony

/**
 * Payload-level protocol for the Sony V2 (WF-1000XM5) dialect.
 *
 * Everything here is derived from `docs/sony-wf1000xm5-protocol.md`, which cites the reference
 * implementations (Gadgetbridge, SonyBridge, SonyHeadphonesClient, HyperEars) line by line.
 */
object SonyProtocol {

    // ---- payload types (COMMAND_1 unless noted) ----
    const val PT_INIT_REQUEST = 0x00
    const val PT_INIT_REPLY = 0x01
    const val PT_FIRMWARE_GET = 0x04
    const val PT_FIRMWARE_RET = 0x05
    const val PT_CODEC_GET = 0x12
    const val PT_CODEC_RET = 0x13
    const val PT_BATTERY_GET = 0x22
    const val PT_BATTERY_RET = 0x23
    const val PT_BATTERY_SET = 0x24
    const val PT_BATTERY_NOTIFY = 0x25
    const val PT_AMBIENT_GET = 0x66
    const val PT_AMBIENT_RET = 0x67
    const val PT_AMBIENT_SET = 0x68
    const val PT_AMBIENT_NOTIFY = 0x69

    // ---- battery sub-types (V2) ----
    const val BATTERY_SINGLE = 0x00
    const val BATTERY_DUAL = 0x09
    const val BATTERY_DUAL2 = 0x01
    const val BATTERY_CASE = 0x0A

    // ---- ambient sound control dialects ----
    /** Plain NC/Ambient dialect; this is what the WF-1000XM5 uses. */
    const val AMBIENT_SUBTYPE_PLAIN = 0x15

    /** Extended dialect that adds wind-noise reduction (other models). */
    const val AMBIENT_SUBTYPE_WIND = 0x17

    /** Original dialect used by the first generation of the protocol. */
    const val AMBIENT_SUBTYPE_V1 = 0x02

    /**
     * Read sub-types to try, in order, until the headset answers.
     *
     * References disagree for the XM5 (Gadgetbridge/HyperEars send `0x15`, SonyBridge sends
     * `0x17`), and this unit answered neither `0x15` nor `0x17` with a data frame, so `0x02` is
     * probed as well.
     */
    val AMBIENT_SUBTYPE_LADDER = listOf(
        AMBIENT_SUBTYPE_PLAIN,
        AMBIENT_SUBTYPE_WIND,
        AMBIENT_SUBTYPE_V1,
    )

    const val AMBIENT_LEVEL_MIN = 0
    const val AMBIENT_LEVEL_MAX = 20

    /** Firmware check reported by Sound Connect for a WF-1000XM5. */
    val XM5_INIT_REPLY = byteArrayOf(0x01, 0x00, 0x03, 0x00, 0x10, 0x04, 0x00, 0x00)

    val INIT_REQUEST = byteArrayOf(PT_INIT_REQUEST.toByte(), 0x00)
    val BATTERY_DUAL_REQUEST = byteArrayOf(PT_BATTERY_GET.toByte(), BATTERY_DUAL.toByte())
    val BATTERY_CASE_REQUEST = byteArrayOf(PT_BATTERY_GET.toByte(), BATTERY_CASE.toByte())
    val FIRMWARE_REQUEST = byteArrayOf(PT_FIRMWARE_GET.toByte(), 0x02)

    fun ambientGetRequest(subtype: Int): ByteArray =
        byteArrayOf(PT_AMBIENT_GET.toByte(), subtype.toByte())

    /** True for the wind-noise selector byte of the extended ambient layout. */
    fun isWindSelector(value: Int): Boolean = value == 0x03 || value == 0x05

    /**
     * Build the ambient-sound SET payload.
     *
     * `68 <subtype> 01 <on> <0=NC|1=Ambient> [wind] <focus> <level>`
     *
     * The byte that selects NC vs Ambient is *not* the mode itself: on the wire the mode is two
     * independent booleans ("enabled" and "ambient vs NC"). There is no Adaptive/Auto mode.
     *
     * [windCapable] must come from what the headset actually reported (an 8-byte reply), not from
     * the sub-type: the WF-1000XM5 answers `0x17` with the *short* 7-byte layout, even though other
     * models use `0x17` for the 8-byte wind layout.
     */
    fun ambientSetRequest(
        subtype: Int,
        mode: SonyNoiseMode,
        ambientLevel: Int,
        focusOnVoice: Boolean,
        windCapable: Boolean = false,
        windNoiseReduction: Boolean = false,
    ): ByteArray {
        val level = ambientLevel.coerceIn(AMBIENT_LEVEL_MIN, AMBIENT_LEVEL_MAX)
        val on = if (mode == SonyNoiseMode.OFF) 0x00 else 0x01
        val ambient = if (mode == SonyNoiseMode.AMBIENT_SOUND) 0x01 else 0x00

        val payload = ArrayList<Byte>(8)
        payload.add(PT_AMBIENT_SET.toByte())
        payload.add(subtype.toByte())
        payload.add(0x01) // commit with confirmation tone (0x00 = silent slider drag)
        payload.add(on.toByte())
        payload.add(ambient.toByte())
        if (windCapable) {
            payload.add(if (windNoiseReduction) 0x03 else 0x02)
        }
        payload.add(if (focusOnVoice) 0x01 else 0x00)
        payload.add(level.toByte())
        return payload.toByteArray()
    }
}

enum class SonyNoiseMode {
    UNKNOWN,
    OFF,
    NOISE_CANCELLING,
    AMBIENT_SOUND,
    ;

    val isActive: Boolean get() = this == NOISE_CANCELLING || this == AMBIENT_SOUND
}

/** A single battery reading. [level] is 0-100. */
data class SonyBudBattery(val level: Int, val charging: Boolean)

data class SonyBatteryState(
    val left: SonyBudBattery? = null,
    val right: SonyBudBattery? = null,
    val case: SonyBudBattery? = null,
)

data class SonyNoiseState(
    val mode: SonyNoiseMode = SonyNoiseMode.UNKNOWN,
    val ambientLevel: Int = 10,
    val focusOnVoice: Boolean = false,
    val windNoiseReduction: Boolean = false,
)

data class SonyHeadsetState(
    val connected: Boolean = false,
    /** 2 for the WF-1000XM5; 1 for older models; 0 until the handshake completes. */
    val protocolVersion: Int = 0,
    val firmware: String? = null,
    val battery: SonyBatteryState = SonyBatteryState(),
    val noise: SonyNoiseState = SonyNoiseState(),
    val ambientSubtype: Int = SonyProtocol.AMBIENT_SUBTYPE_PLAIN,
) {
    /** Compact single-line form, used for the protocol log. */
    override fun toString(): String {
        fun cell(battery: SonyBudBattery?): String =
            battery?.let { "${it.level}${if (it.charging) "+" else ""}" } ?: "-"

        return "v$protocolVersion fw=${firmware ?: "-"} " +
            "L=${cell(this.battery.left)} R=${cell(this.battery.right)} " +
            "case=${cell(this.battery.case)} " +
            "noise=${noise.mode} lvl=${noise.ambientLevel} focus=${noise.focusOnVoice}"
    }
}

/**
 * Stateless frame -> state decoders. Every function returns null when the frame is not the one it
 * understands, so the session can simply chain them.
 */
object SonyParser {

    fun initReplyVersion(frame: SonyFrame): Int? {
        if (frame.payloadType != SonyProtocol.PT_INIT_REPLY) return null
        return when (frame.payload.size) {
            4 -> 1
            8 -> 2
            else -> null
        }
    }

    fun firmware(frame: SonyFrame): String? {
        if (frame.payloadType != SonyProtocol.PT_FIRMWARE_RET) return null
        if (frame.payload.size < 4) return null
        val length = frame.payloadByte(2)
        if (length <= 0 || 3 + length > frame.payload.size) return null
        val text = String(frame.payload, 3, length, Charsets.US_ASCII)
        return text.takeIf { it.matches(Regex("^[0-9.\\-a-zA-Z_]+$")) }
    }

    /**
     * Parse a battery RET (`0x23`) or NOTIFY (`0x25`) frame.
     *
     * A bud level of 0 means "bud absent", not "empty", so it is reported as null. The charging
     * byte is validated too: a value other than 0/1 means the pair at that offset is not a
     * level/charging pair on this firmware, so it is rejected rather than shown as a plausible lie.
     */
    fun battery(frame: SonyFrame): SonyBatteryState? {
        if (frame.payloadType != SonyProtocol.PT_BATTERY_RET &&
            frame.payloadType != SonyProtocol.PT_BATTERY_NOTIFY
        ) {
            return null
        }

        val size = frame.payload.size
        return when (frame.payloadByte(1)) {
            SonyProtocol.BATTERY_DUAL, SonyProtocol.BATTERY_DUAL2 -> {
                if (size < 6) return null
                SonyBatteryState(
                    left = bud(frame, 2, 3),
                    right = bud(frame, 4, 5),
                    case = if (size >= 8) bud(frame, 6, 7) else null,
                )
            }
            SonyProtocol.BATTERY_CASE -> {
                if (size < 4) return null
                SonyBatteryState(case = bud(frame, 2, 3))
            }
            SonyProtocol.BATTERY_SINGLE -> {
                if (size < 4) return null
                SonyBatteryState(left = bud(frame, 2, 3))
            }
            else -> null
        }
    }

    private fun bud(frame: SonyFrame, levelIndex: Int, chargingIndex: Int): SonyBudBattery? {
        val level = frame.payloadByte(levelIndex)
        val charging = frame.payloadByte(chargingIndex)
        if (level !in 1..100) return null
        if (charging != 0 && charging != 1) return null
        return SonyBudBattery(level, charging == 1)
    }

    /**
     * Parse an ambient-sound RET (`0x67`) or NOTIFY (`0x69`) frame.
     *
     * Two layouts exist on the wire and the sub-type alone does **not** select between them. The
     * WF-1000XM5 (firmware 6.1.0) answers `0x17` with the short layout:
     *
     * ```
     * 67 17 01 <on> <0=NC|1=AS> <focus> <level>      (7 bytes)  <- verified on hardware
     * ```
     *
     * Other models answer `0x17` with the extended layout that carries a wind selector:
     *
     * ```
     * 67 17 01 <on> <0=NC|1=AS> <wind 0x03|0x05> <focus> <level>   (8 bytes)
     * ```
     */
    fun ambient(frame: SonyFrame): SonyNoiseState? {
        if (frame.payloadType != SonyProtocol.PT_AMBIENT_RET &&
            frame.payloadType != SonyProtocol.PT_AMBIENT_NOTIFY
        ) {
            return null
        }

        val subtype = frame.payloadByte(1)
        if (subtype == SonyProtocol.AMBIENT_SUBTYPE_V1) return ambientV1(frame)
        if (subtype != SonyProtocol.AMBIENT_SUBTYPE_PLAIN &&
            subtype != SonyProtocol.AMBIENT_SUBTYPE_WIND
        ) {
            return null
        }

        val size = frame.payload.size
        val on = frame.payloadByte(3) == 1

        if (size >= 8 && SonyProtocol.isWindSelector(frame.payloadByte(5))) {
            return SonyNoiseState(
                mode = mode(on, ambient = false),
                ambientLevel = frame.payloadByte(7).coerceIn(
                    SonyProtocol.AMBIENT_LEVEL_MIN,
                    SonyProtocol.AMBIENT_LEVEL_MAX,
                ),
                focusOnVoice = frame.payloadByte(6) == 1,
                windNoiseReduction = true,
            )
        }

        if (size < 7) return null
        return SonyNoiseState(
            mode = mode(on, ambient = frame.payloadByte(4) == 1),
            ambientLevel = frame.payloadByte(6).coerceIn(
                SonyProtocol.AMBIENT_LEVEL_MIN,
                SonyProtocol.AMBIENT_LEVEL_MAX,
            ),
            focusOnVoice = frame.payloadByte(5) == 1,
        )
    }

    /** `67 02 <on> <windCap> <mode> 01 <focus> <level>` */
    private fun ambientV1(frame: SonyFrame): SonyNoiseState? {
        if (frame.payload.size < 8) return null
        val selector = frame.payloadByte(4)
        val windCapable = frame.payloadByte(3) == 0x00
        val ambient = if (windCapable) selector == 0x00 else selector == 0x02
        return SonyNoiseState(
            mode = mode(frame.payloadByte(2) == 1, ambient),
            ambientLevel = frame.payloadByte(7).coerceIn(
                SonyProtocol.AMBIENT_LEVEL_MIN,
                SonyProtocol.AMBIENT_LEVEL_MAX,
            ),
            focusOnVoice = frame.payloadByte(6) == 1,
            windNoiseReduction = windCapable && selector == 0x01,
        )
    }

    /** True when the last ambient reply used the extended (wind-capable) layout. */
    fun ambientUsesWindLayout(frame: SonyFrame): Boolean =
        frame.payload.size >= 8 && SonyProtocol.isWindSelector(frame.payloadByte(5))

    private fun mode(on: Boolean, ambient: Boolean): SonyNoiseMode = when {
        !on -> SonyNoiseMode.OFF
        ambient -> SonyNoiseMode.AMBIENT_SOUND
        else -> SonyNoiseMode.NOISE_CANCELLING
    }
}
