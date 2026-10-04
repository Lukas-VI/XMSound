package moe.yanhe.xmsound.pods.sony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests. Every expected frame here is a published test vector from the reference
 * implementations (see `docs/sony-wf1000xm5-protocol.md` §2.3, §3.2-§3.4), so a failure means this
 * implementation diverged from the real protocol.
 */
class SonyWireCodecTest {

    private fun frame(vararg bytes: Int): ByteArray =
        ByteArray(bytes.size) { bytes[it].toByte() }

    private fun decodeRaw(raw: ByteArray): SonyFrame? {
        val frames = SonyFrameDecoder().offer(raw)
        assertEquals("expected exactly one frame", 1, frames.size)
        return frames.first()
    }

    // ------------------------------------------------------------ encoding

    @Test
    fun `init request matches the documented vector`() {
        val encoded = SonyCodec.encode(SonyMessageType.COMMAND_1, 0, SonyProtocol.INIT_REQUEST)
        assertArrayEquals(
            frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x0e, 0x3c),
            encoded,
        )
    }

    @Test
    fun `codec query with sequence 1 matches the gadgetbridge vector`() {
        val encoded = SonyCodec.encode(
            SonyMessageType.COMMAND_1,
            1,
            byteArrayOf(SonyProtocol.PT_CODEC_GET.toByte(), 0x02),
        )
        assertArrayEquals(
            frame(0x3e, 0x0c, 0x01, 0x00, 0x00, 0x00, 0x02, 0x12, 0x02, 0x23, 0x3c),
            encoded,
        )
    }

    @Test
    fun `ambient get uses the plain xm5 dialect`() {
        val encoded = SonyCodec.encode(
            SonyMessageType.COMMAND_1,
            0,
            SonyProtocol.ambientGetRequest(SonyProtocol.AMBIENT_SUBTYPE_PLAIN),
        )
        assertArrayEquals(
            frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x02, 0x66, 0x15, 0x89, 0x3c),
            encoded,
        )
    }

    @Test
    fun `ack carries the inverted sequence number`() {
        assertArrayEquals(
            frame(0x3e, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x02, 0x3c),
            SonyCodec.encodeAck(0),
        )
        assertArrayEquals(
            frame(0x3e, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x3c),
            SonyCodec.encodeAck(1),
        )
    }

    @Test
    fun `checksum is an additive sum and reserved bytes are escaped`() {
        val encoded = SonyCodec.encode(
            SonyMessageType.COMMAND_1,
            0,
            byteArrayOf(0x3e, 0x3d, 0x3c),
        )
        assertArrayEquals(
            frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x03, 0x3d, 0x2e, 0x3d, 0x2d, 0x3d, 0x2c, 0xc6, 0x3c),
            encoded,
        )
    }

    // ------------------------------------------------------------ ambient set

    @Test
    fun `set ambient sound level 20 matches the documented vector`() {
        val payload = SonyProtocol.ambientSetRequest(
            subtype = SonyProtocol.AMBIENT_SUBTYPE_PLAIN,
            mode = SonyNoiseMode.AMBIENT_SOUND,
            ambientLevel = 20,
            focusOnVoice = false,
        )
        assertArrayEquals(frame(0x68, 0x15, 0x01, 0x01, 0x01, 0x00, 0x14), payload)
        assertArrayEquals(
            frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x07, 0x68, 0x15, 0x01, 0x01, 0x01, 0x00, 0x14, 0xa7, 0x3c),
            SonyCodec.encode(SonyMessageType.COMMAND_1, 0, payload),
        )
    }

    @Test
    fun `set noise cancelling clears the ambient selector`() {
        val payload = SonyProtocol.ambientSetRequest(
            subtype = SonyProtocol.AMBIENT_SUBTYPE_PLAIN,
            mode = SonyNoiseMode.NOISE_CANCELLING,
            ambientLevel = 0,
            focusOnVoice = false,
        )
        assertArrayEquals(frame(0x68, 0x15, 0x01, 0x01, 0x00, 0x00, 0x00), payload)
    }

    @Test
    fun `set off clears both the enable and the selector`() {
        val payload = SonyProtocol.ambientSetRequest(
            subtype = SonyProtocol.AMBIENT_SUBTYPE_PLAIN,
            mode = SonyNoiseMode.OFF,
            ambientLevel = 0,
            focusOnVoice = false,
        )
        assertArrayEquals(frame(0x68, 0x15, 0x01, 0x00, 0x00, 0x00, 0x00), payload)
    }

    @Test
    fun `ambient level is clamped to the wire range`() {
        val tooHigh = SonyProtocol.ambientSetRequest(
            SonyProtocol.AMBIENT_SUBTYPE_PLAIN, SonyNoiseMode.AMBIENT_SOUND, 99, false,
        )
        assertEquals(0x14, tooHigh.last().toInt() and 0xFF)

        val tooLow = SonyProtocol.ambientSetRequest(
            SonyProtocol.AMBIENT_SUBTYPE_PLAIN, SonyNoiseMode.AMBIENT_SOUND, -5, false,
        )
        assertEquals(0x00, tooLow.last().toInt() and 0xFF)
    }

    // ------------------------------------------------------------ decoding

    @Test
    fun `decoder reassembles a frame split across reads`() {
        val raw = frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x0e, 0x3c)
        val decoder = SonyFrameDecoder()
        assertTrue(decoder.offer(raw, 0, 4).isEmpty())
        assertTrue(decoder.offer(raw, 4, 3).isEmpty())
        val frames = decoder.offer(raw, 7, raw.size - 7)
        assertEquals(1, frames.size)
        assertEquals(SonyMessageType.COMMAND_1, frames[0].type)
    }

    @Test
    fun `decoder splits several frames from one read`() {
        val ack = SonyCodec.encodeAck(0)
        val init = SonyCodec.encode(SonyMessageType.COMMAND_1, 0, SonyProtocol.INIT_REQUEST)
        val frames = SonyFrameDecoder().offer(ack + init)
        assertEquals(2, frames.size)
        assertTrue(frames[0].isAck)
        assertEquals(SonyProtocol.PT_INIT_REQUEST, frames[1].payloadType)
    }

    @Test
    fun `decoder rejects a corrupted checksum`() {
        val raw = frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0xff, 0x3c)
        assertTrue(SonyFrameDecoder().offer(raw).isEmpty())
    }

    @Test
    fun `decoder resynchronises after garbage`() {
        val raw = frame(0x11, 0x22, 0x33) +
            SonyCodec.encode(SonyMessageType.COMMAND_1, 0, SonyProtocol.BATTERY_DUAL_REQUEST)
        val frames = SonyFrameDecoder().offer(raw)
        assertEquals(1, frames.size)
        assertEquals(SonyProtocol.PT_BATTERY_GET, frames[0].payloadType)
    }

    // ------------------------------------------------------------ parsing

    @Test
    fun `init reply length selects the protocol generation`() {
        val v2 = decodeRaw(frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x08, 0x01, 0x00, 0x03, 0x00, 0x10, 0x04, 0x00, 0x00, 0x2c, 0x3c))
        assertNotNull(v2)
        assertEquals(2, SonyParser.initReplyVersion(v2!!))
        assertArrayEquals(SonyProtocol.XM5_INIT_REPLY, v2.payload)

        val v1 = decodeRaw(frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x40, 0x10, 0x61, 0x3c))
        assertNotNull(v1)
        assertEquals(1, SonyParser.initReplyVersion(v1!!))
    }

    @Test
    fun `dual battery reply yields left and right`() {
        val f = decodeRaw(frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x06, 0x23, 0x09, 0x5a, 0x00, 0x5b, 0x01, 0xf4, 0x3c))!!
        val battery = SonyParser.battery(f)
        assertNotNull(battery)
        assertEquals(SonyBudBattery(90, false), battery!!.left)
        assertEquals(SonyBudBattery(91, true), battery.right)
        assertNull(battery.case)
    }

    @Test
    fun `case battery reply yields the case`() {
        val f = decodeRaw(frame(0x3e, 0x0c, 0x01, 0x00, 0x00, 0x00, 0x04, 0x23, 0x0a, 0x64, 0x01, 0xa3, 0x3c))!!
        val battery = SonyParser.battery(f)!!
        assertEquals(SonyBudBattery(100, true), battery.case)
        assertNull(battery.left)
    }

    @Test
    fun `an absent bud reports no level rather than an empty battery`() {
        val f = decodeRaw(frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x06, 0x25, 0x09, 0x00, 0x00, 0x5b, 0x00, 0x9b, 0x3c))!!
        val battery = SonyParser.battery(f)!!
        assertNull(battery.left)
        assertEquals(SonyBudBattery(91, false), battery.right)
    }

    @Test
    fun `ambient notify decodes ambient sound with level and focus`() {
        val f = decodeRaw(frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x07, 0x67, 0x15, 0x01, 0x01, 0x01, 0x00, 0x14, 0xa6, 0x3c))!!
        val noise = SonyParser.ambient(f)!!
        assertEquals(SonyNoiseMode.AMBIENT_SOUND, noise.mode)
        assertEquals(20, noise.ambientLevel)
        assertEquals(false, noise.focusOnVoice)
    }

    @Test
    fun `ambient reply decodes noise cancelling`() {
        val f = decodeRaw(frame(0x3e, 0x0c, 0x01, 0x00, 0x00, 0x00, 0x07, 0x67, 0x15, 0x01, 0x01, 0x00, 0x00, 0x14, 0xa6, 0x3c))!!
        assertEquals(SonyNoiseMode.NOISE_CANCELLING, SonyParser.ambient(f)!!.mode)
    }

    @Test
    fun `ambient reply decodes off regardless of the selector`() {
        val f = decodeRaw(frame(0x3e, 0x0c, 0x01, 0x00, 0x00, 0x00, 0x07, 0x67, 0x15, 0x01, 0x00, 0x00, 0x00, 0x14, 0xa5, 0x3c))!!
        assertEquals(SonyNoiseMode.OFF, SonyParser.ambient(f)!!.mode)
    }

    @Test
    fun `firmware reply is decoded as ascii`() {
        val f = decodeRaw(frame(0x3e, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x08, 0x05, 0x02, 0x05, 0x32, 0x2e, 0x30, 0x2e, 0x31, 0x0f, 0x3c))!!
        assertEquals("2.0.1", SonyParser.firmware(f))
    }

    @Test
    fun `unrelated frames are ignored by every parser`() {
        val ack = decodeRaw(SonyCodec.encodeAck(0))!!
        assertNull(SonyParser.battery(ack))
        assertNull(SonyParser.ambient(ack))
        assertNull(SonyParser.firmware(ack))
        assertNull(SonyParser.initReplyVersion(ack))
    }
}
