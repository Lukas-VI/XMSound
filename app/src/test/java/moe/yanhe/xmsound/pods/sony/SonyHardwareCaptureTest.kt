package moe.yanhe.xmsound.pods.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests built from a real capture against the target hardware
 * (Xiaomi 13 Ultra / HyperOS OS3.0.305, WF-1000XM5 with firmware 6.1.0).
 *
 * These payloads differ from every published reference sample, which is exactly why they are
 * pinned here: they are the ground truth for this unit.
 */
class SonyHardwareCaptureTest {

    private fun command(vararg payload: Int) =
        SonyFrame(
            SonyMessageType.COMMAND_1,
            0,
            ByteArray(payload.size) { payload[it].toByte() },
        )

    // ------------------------------------------------------------ handshake

    @Test
    fun `captured init reply identifies protocol v2`() {
        // RX payload=0100030030180000
        val frame = command(0x01, 0x00, 0x03, 0x00, 0x30, 0x18, 0x00, 0x00)
        assertEquals(2, SonyParser.initReplyVersion(frame))
    }

    // ------------------------------------------------------------ battery

    @Test
    fun `captured dual battery reply yields both buds`() {
        // RX payload=2309640064006464  (8 bytes - two longer than any reference sample)
        val battery = SonyParser.battery(command(0x23, 0x09, 0x64, 0x00, 0x64, 0x00, 0x64, 0x64))
        assertNotNull(battery)
        assertEquals(SonyBudBattery(100, false), battery!!.left)
        assertEquals(SonyBudBattery(100, false), battery.right)
    }

    @Test
    fun `the unknown trailing bytes of the dual reply are not reported as the case`() {
        // The pair at offset 6 (0x64 0x64) is not a level/charging pair: 0x64 is not a valid
        // charging flag, so the parser must refuse it rather than show a bogus case level.
        val battery = SonyParser.battery(command(0x23, 0x09, 0x64, 0x00, 0x64, 0x00, 0x64, 0x64))!!
        assertNull(battery.case)
    }

    @Test
    fun `captured case battery reply yields the case level`() {
        // RX payload=230a27001e
        val battery = SonyParser.battery(command(0x23, 0x0a, 0x27, 0x00, 0x1e))
        assertNotNull(battery)
        assertEquals(SonyBudBattery(39, false), battery!!.case)
    }

    @Test
    fun `captured dual2 battery reply yields both buds`() {
        // RX payload=230164006400
        val battery = SonyParser.battery(command(0x23, 0x01, 0x64, 0x00, 0x64, 0x00))!!
        assertEquals(SonyBudBattery(100, false), battery.left)
        assertEquals(SonyBudBattery(100, false), battery.right)
    }

    @Test
    fun `a bud reported at zero is unavailable rather than empty`() {
        val battery = SonyParser.battery(command(0x23, 0x09, 0x00, 0x00, 0x5b, 0x00, 0x00, 0x00))!!
        assertNull(battery.left)
        assertEquals(SonyBudBattery(91, false), battery.right)
    }

    // ------------------------------------------------------------ firmware

    @Test
    fun `captured firmware reply decodes to the installed version`() {
        // RX payload=050205362e312e30  -> "6.1.0"
        assertEquals(
            "6.1.0",
            SonyParser.firmware(command(0x05, 0x02, 0x05, 0x36, 0x2e, 0x31, 0x2e, 0x30)),
        )
    }

    // ------------------------------------------------------------ ambient sound

    @Test
    fun `captured ambient reply uses the short layout even on subtype 0x17`() {
        // RX payload=6717010100000a  -> noise cancelling, level 10, focus off
        // The reference implementations only expect the short layout for subtype 0x15, so this
        // frame is dropped by a naively written parser.
        val noise = SonyParser.ambient(command(0x67, 0x17, 0x01, 0x01, 0x00, 0x00, 0x0a))
        assertNotNull(noise)
        assertEquals(SonyNoiseMode.NOISE_CANCELLING, noise!!.mode)
        assertEquals(10, noise.ambientLevel)
        assertFalse(noise.focusOnVoice)
        assertFalse(noise.windNoiseReduction)
    }

    @Test
    fun `the captured ambient reply does not claim a wind layout`() {
        val frame = command(0x67, 0x17, 0x01, 0x01, 0x00, 0x00, 0x0a)
        assertFalse(SonyParser.ambientUsesWindLayout(frame))
    }

    @Test
    fun `the extended eight byte layout is still decoded for wind capable models`() {
        // 67 17 01 <on> <AS> <wind 0x03> <focus> <level>
        val noise = SonyParser.ambient(command(0x67, 0x17, 0x01, 0x01, 0x01, 0x03, 0x00, 0x14))!!
        assertTrue(noise.windNoiseReduction)
        assertEquals(20, noise.ambientLevel)
        assertEquals(SonyNoiseMode.NOISE_CANCELLING, noise.mode)
    }

    @Test
    fun `short layout ambient sound mode is decoded`() {
        // 67 17 01 01 01 <focus> <level>  -> ambient sound
        val noise = SonyParser.ambient(command(0x67, 0x17, 0x01, 0x01, 0x01, 0x01, 0x0a))!!
        assertEquals(SonyNoiseMode.AMBIENT_SOUND, noise.mode)
        assertEquals(10, noise.ambientLevel)
        assertTrue(noise.focusOnVoice)
    }

    @Test
    fun `off is decoded from the short layout`() {
        val noise = SonyParser.ambient(command(0x67, 0x17, 0x01, 0x00, 0x00, 0x00, 0x0a))!!
        assertEquals(SonyNoiseMode.OFF, noise.mode)
    }

    // ------------------------------------------------------------ request building

    @Test
    fun `the xm5 ambient set uses the short seven byte layout`() {
        // The headset reported the short layout, so the SET must not insert a wind byte even
        // though the sub-type is 0x17.
        val payload = SonyProtocol.ambientSetRequest(
            subtype = SonyProtocol.AMBIENT_SUBTYPE_WIND,
            mode = SonyNoiseMode.NOISE_CANCELLING,
            ambientLevel = 10,
            focusOnVoice = false,
            windCapable = false,
        )
        assertEquals(7, payload.size)
        assertEquals(
            listOf(0x68, 0x17, 0x01, 0x01, 0x00, 0x00, 0x0a),
            payload.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `a wind capable model gets the wind selector byte`() {
        val payload = SonyProtocol.ambientSetRequest(
            subtype = SonyProtocol.AMBIENT_SUBTYPE_WIND,
            mode = SonyNoiseMode.NOISE_CANCELLING,
            ambientLevel = 10,
            focusOnVoice = false,
            windCapable = true,
            windNoiseReduction = false,
        )
        assertEquals(8, payload.size)
        assertEquals(0x02, payload[5].toInt() and 0xFF)
    }

    // ------------------------------------------------------------ frame building

    @Test
    fun `the captured battery request frames are byte exact`() {
        // TX 3e 0c 01 00 00 00 02 22 09 3a 3c   (DUAL, sequence 1)
        assertEquals(
            listOf(0x3e, 0x0c, 0x01, 0x00, 0x00, 0x00, 0x02, 0x22, 0x09, 0x3a, 0x3c),
            SonyCodec.encode(SonyMessageType.COMMAND_1, 1, SonyProtocol.BATTERY_DUAL_REQUEST)
                .map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `the captured ambient request frame is byte exact`() {
        // TX 3e 0c 01 00 00 00 02 66 17 8c 3c   (ambient get, subtype 0x17, sequence 1)
        assertEquals(
            listOf(0x3e, 0x0c, 0x01, 0x00, 0x00, 0x00, 0x02, 0x66, 0x17, 0x8c, 0x3c),
            SonyCodec.encode(
                SonyMessageType.COMMAND_1,
                1,
                SonyProtocol.ambientGetRequest(SonyProtocol.AMBIENT_SUBTYPE_WIND),
            ).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `the captured firmware request frame is byte exact`() {
        // TX 3e 0c 00 00 00 00 02 04 02 14 3c
        assertEquals(
            listOf(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x02, 0x04, 0x02, 0x14, 0x3c),
            SonyCodec.encode(SonyMessageType.COMMAND_1, 0, SonyProtocol.FIRMWARE_REQUEST)
                .map { it.toInt() and 0xFF },
        )
    }
}
