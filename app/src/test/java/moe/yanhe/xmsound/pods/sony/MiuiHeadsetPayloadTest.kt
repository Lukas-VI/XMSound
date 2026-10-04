package moe.yanhe.xmsound.pods.sony

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The MIUI status string is what makes HyperOS' headset UI show per-bud battery and the current
 * noise mode, so its exact layout is pinned here.
 */
class MiuiHeadsetPayloadTest {

    @Test
    fun `payload has sixteen comma separated slots`() {
        val payload = MiuiHeadsetPayload.build(
            left = SonyBudBattery(93, false),
            right = SonyBudBattery(88, false),
            case = SonyBudBattery(30, false),
            mode = SonyNoiseMode.NOISE_CANCELLING,
            focusOnVoice = false,
        )
        assertEquals(16, payload.split(",").size)
    }

    @Test
    fun `battery levels land in slots 0 1 and 2`() {
        val slots = MiuiHeadsetPayload.build(
            left = SonyBudBattery(93, false),
            right = SonyBudBattery(88, true),
            case = SonyBudBattery(30, false),
            mode = SonyNoiseMode.AMBIENT_SOUND,
            focusOnVoice = false,
        ).split(",")

        assertEquals("93", slots[0])
        // 88 with bit 7 set while charging.
        assertEquals((88 or 128).toString(), slots[1])
        assertEquals("30", slots[2])
    }

    @Test
    fun `an absent part is reported as 255 not as empty`() {
        val slots = MiuiHeadsetPayload.build(
            left = null,
            right = SonyBudBattery(88, false),
            case = null,
            mode = SonyNoiseMode.OFF,
            focusOnVoice = false,
        ).split(",")

        assertEquals("255", slots[0])
        assertEquals("88", slots[1])
        assertEquals("255", slots[2])
    }

    @Test
    fun `noise mode lands in slot 7 with the miui codes`() {
        fun slot7(mode: SonyNoiseMode, focus: Boolean = false) = MiuiHeadsetPayload.build(
            left = null, right = null, case = null, mode = mode, focusOnVoice = focus,
        ).split(",")[7]

        assertEquals("0000", slot7(SonyNoiseMode.OFF))
        assertEquals("0100", slot7(SonyNoiseMode.NOISE_CANCELLING))
        assertEquals("0200", slot7(SonyNoiseMode.AMBIENT_SOUND))
        assertEquals("0201", slot7(SonyNoiseMode.AMBIENT_SOUND, focus = true))
    }

    @Test
    fun `constant slots match what the ui expects`() {
        val slots = MiuiHeadsetPayload.build(
            left = null, right = null, case = null,
            mode = SonyNoiseMode.UNKNOWN, focusOnVoice = false,
        ).split(",")

        assertEquals("true", slots[8])
        assertEquals("00", slots[11])
        assertEquals("00", slots[13])
        assertEquals("00", slots[14])
        // Slots the XM5 has no notion of stay empty.
        listOf(3, 4, 5, 6, 9, 10, 12, 15).forEach {
            assertEquals("slot $it should be empty", "", slots[it])
        }
    }

    @Test
    fun `noise codes round trip back to a mode`() {
        assertEquals(SonyNoiseMode.OFF, MiuiHeadsetPayload.modeForNoiseCode("0000"))
        assertEquals(SonyNoiseMode.NOISE_CANCELLING, MiuiHeadsetPayload.modeForNoiseCode("0100"))
        assertEquals(SonyNoiseMode.AMBIENT_SOUND, MiuiHeadsetPayload.modeForNoiseCode("0200"))
        assertEquals(SonyNoiseMode.AMBIENT_SOUND, MiuiHeadsetPayload.modeForNoiseCode("0201"))
        // Codes this headset cannot represent must not be invented.
        assertEquals(null, MiuiHeadsetPayload.modeForNoiseCode("0103"))
        assertEquals(null, MiuiHeadsetPayload.modeForNoiseCode(null))
    }

    @Test
    fun `state helper uses the same layout`() {
        val state = SonyHeadsetState(
            connected = true,
            protocolVersion = 2,
            battery = SonyBatteryState(
                left = SonyBudBattery(93, false),
                right = SonyBudBattery(88, false),
                case = SonyBudBattery(30, false),
            ),
            noise = SonyNoiseState(mode = SonyNoiseMode.NOISE_CANCELLING, ambientLevel = 16),
        )
        val slots = MiuiHeadsetPayload.build(state).split(",")
        assertEquals("93", slots[0])
        assertEquals("88", slots[1])
        assertEquals("30", slots[2])
        assertEquals("0100", slots[7])
    }
}
