package moe.yanhe.xmsound.pods.sony

import java.io.ByteArrayOutputStream

/**
 * Sony headphone SPP wire format.
 *
 * ```
 * 0x3E | ESCAPE( <TYPE> <SEQ> <LEN32_BE> <PAYLOAD...> <CHECKSUM> ) | 0x3C
 * ```
 *
 * The checksum is a plain mod-256 additive sum over the unescaped body (excluding the checksum
 * byte itself) - this protocol does **not** use a CRC.
 *
 * See `docs/sony-wf1000xm5-protocol.md` for the full specification and its sources.
 */
object SonyWire {
    const val HEADER = 0x3E
    const val TRAILER = 0x3C
    const val ESCAPE = 0x3D
    const val ESCAPE_MASK = 0xEF
    const val RESTORE_BIT = 0x10

    /** Upper bound on an unescaped body; guards against a desynchronised stream. */
    const val MAX_BODY = 16 * 1024

    /** Smallest legal body: type + seq + 4-byte length + checksum. */
    const val MIN_BODY = 7
}

enum class SonyMessageType(val id: Int) {
    ACK(0x01),
    COMMAND_1(0x0C),
    COMMAND_2(0x0E);

    companion object {
        fun of(id: Int): SonyMessageType? = entries.firstOrNull { it.id == id }
    }
}

/** A decoded, checksum-verified frame. */
class SonyFrame(
    val type: SonyMessageType,
    val sequence: Int,
    val payload: ByteArray,
) {
    /** First payload byte (the payload type), or -1 for an empty payload (ACK). */
    val payloadType: Int = if (payload.isEmpty()) -1 else payload[0].toInt() and 0xFF

    val isAck: Boolean get() = type == SonyMessageType.ACK

    fun payloadByte(index: Int): Int =
        if (index in payload.indices) payload[index].toInt() and 0xFF else -1

    fun toHex(): String = buildString {
        append("type=0x%02X seq=%d".format(type.id, sequence))
        append(" len=%d".format(payload.size))
        if (payload.isNotEmpty()) {
            append(" payload=")
            payload.forEach { append("%02x".format(it.toInt() and 0xFF)) }
        }
    }

    override fun toString(): String = "SonyFrame(${toHex()})"
}

object SonyCodec {

    /** 8-bit additive sum (LRC) over `body[0, endExclusive)`. */
    fun checksum(body: ByteArray, endExclusive: Int): Int {
        var sum = 0
        for (i in 0 until endExclusive) sum = (sum + (body[i].toInt() and 0xFF)) and 0xFF
        return sum
    }

    /** Build the raw on-the-wire frame (escaped and delimited) for [payload]. */
    fun encode(type: SonyMessageType, sequence: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(6 + payload.size + 1)
        body[0] = type.id.toByte()
        body[1] = (sequence and 0xFF).toByte()
        val n = payload.size
        body[2] = (n ushr 24).toByte()
        body[3] = (n ushr 16).toByte()
        body[4] = (n ushr 8).toByte()
        body[5] = n.toByte()
        payload.copyInto(body, 6)
        body[body.size - 1] = checksum(body, body.size - 1).toByte()
        return escape(body)
    }

    /** Convenience: an ACK acknowledges [receivedSequence] with the inverted sequence number. */
    fun encodeAck(receivedSequence: Int): ByteArray =
        encode(SonyMessageType.ACK, 1 - (receivedSequence and 1), ByteArray(0))

    private fun escape(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 8)
        out.write(SonyWire.HEADER)
        for (b in body) {
            val v = b.toInt() and 0xFF
            if (v == SonyWire.HEADER || v == SonyWire.TRAILER || v == SonyWire.ESCAPE) {
                out.write(SonyWire.ESCAPE)
                out.write(v and SonyWire.ESCAPE_MASK)
            } else {
                out.write(v)
            }
        }
        out.write(SonyWire.TRAILER)
        return out.toByteArray()
    }

    /**
     * Verify and decode an unescaped body. Returns null when the body is malformed or the
     * checksum does not match, so callers can simply ignore it.
     */
    fun decodeBody(body: ByteArray, length: Int): SonyFrame? {
        if (length < SonyWire.MIN_BODY || length > SonyWire.MAX_BODY) return null

        val type = SonyMessageType.of(body[0].toInt() and 0xFF) ?: return null
        val sequence = body[1].toInt() and 0xFF
        val payloadLength = ((body[2].toInt() and 0xFF) shl 24) or
            ((body[3].toInt() and 0xFF) shl 16) or
            ((body[4].toInt() and 0xFF) shl 8) or
            (body[5].toInt() and 0xFF)

        if (payloadLength < 0 || 6 + payloadLength + 1 != length) return null
        if (checksum(body, length - 1) != (body[length - 1].toInt() and 0xFF)) return null

        return SonyFrame(type, sequence, body.copyOfRange(6, 6 + payloadLength))
    }
}

/**
 * Incremental RFCOMM stream decoder.
 *
 * RFCOMM is a byte stream, so frames arrive split across reads and several frames may be
 * coalesced into one read. A `0x3C` that follows an escape byte never terminates a frame, and the
 * first unescaped `0x3C` after a `0x3E` always does - which is what lets the decoder resynchronise
 * after a bad frame.
 */
class SonyFrameDecoder {
    private val body = ByteArray(SonyWire.MAX_BODY)
    private var length = 0
    private var inFrame = false
    private var escaped = false

    /** Bytes seen since the last successful frame decode; used for diagnostics. */
    var droppedBytes: Long = 0
        private set

    @Synchronized
    fun offer(data: ByteArray, offset: Int = 0, count: Int = data.size): List<SonyFrame> {
        val frames = ArrayList<SonyFrame>(2)
        val end = offset + count
        for (i in offset until end) {
            val b = data[i].toInt() and 0xFF

            if (!inFrame) {
                if (b == SonyWire.HEADER) {
                    inFrame = true
                    escaped = false
                    length = 0
                } else {
                    droppedBytes++
                }
                continue
            }

            if (escaped) {
                escaped = false
                if (length < SonyWire.MAX_BODY) {
                    body[length++] = (b or SonyWire.RESTORE_BIT).toByte()
                } else {
                    inFrame = false
                    droppedBytes++
                }
                continue
            }

            when (b) {
                SonyWire.ESCAPE -> escaped = true
                SonyWire.TRAILER -> {
                    inFrame = false
                    val frame = SonyCodec.decodeBody(body, length)
                    if (frame != null) frames.add(frame) else droppedBytes++
                    length = 0
                }
                else -> {
                    if (length < SonyWire.MAX_BODY) {
                        body[length++] = b.toByte()
                    } else {
                        inFrame = false
                        length = 0
                        droppedBytes++
                    }
                }
            }
        }
        return frames
    }

    @Synchronized
    fun reset() {
        inFrame = false
        escaped = false
        length = 0
    }
}
