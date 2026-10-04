package moe.yanhe.xmsound.pods.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * One serialised SPP session with a Sony headset.
 *
 * Rules enforced here (see `docs/sony-wf1000xm5-protocol.md` §2.5, §3.2):
 *  - exactly one request may be outstanding; the next is sent only after the device answered,
 *  - every `COMMAND_1`/`COMMAND_2` frame received is ACKed immediately, with the inverted
 *    sequence number, before its payload is handled,
 *  - the host adopts the sequence number carried by the device ACK,
 *  - the init request is retried because some units ignore the first frame.
 *
 * All socket work happens on [worker] / [reader]; callers only enqueue commands, so this class is
 * safe to drive from the UI thread.
 */
class SonyHeadsetSession(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?,
    private val listener: Listener,
) {

    interface Listener {
        fun onStateChanged(state: SonyHeadsetState)
        fun onConnectionChanged(connected: Boolean, message: String?)
        /** [direction] is '>' for host->headset and '<' for headset->host. */
        fun onTraffic(direction: Char, text: String)
    }

    companion object {
        /** Sony V2 service, used by the WF-1000XM5. */
        val UUID_V2: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")

        /** Sony V1 service, used by older models; kept as a fallback. */
        val UUID_V1: UUID = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")

        private const val TAG = "XMSound-Sony"

        /** The stack needs a moment after the ACL link comes up, otherwise connect() throws. */
        private const val PRE_CONNECT_DELAY_MS = 600L
        private const val CONNECT_TIMEOUT_MS = 12_000L

        private const val INIT_TIMEOUT_MS = 1_250L
        private const val INIT_MAX_ATTEMPTS = 3
        private const val REQUEST_TIMEOUT_MS = 2_500L

        private const val REFRESH_INTERVAL_MS = 30_000L

        /** How long the worker parks before re-checking the queue and the refresh deadline. */
        private const val WORKER_POLL_MS = 500L

        /** Settle time after a fire-and-forget SET, so the next request is still serialised. */
        private const val SET_SETTLE_MS = 120L

        private val EMPTY_PAYLOAD = ByteArray(0)

        /**
         * VERIFIED ON HARDWARE: the host ACK must carry the *inverted* sequence number of the frame
         * it acknowledges. Echoing the received sequence instead makes the WF-1000XM5 stop
         * answering and repeat its last frame indefinitely - every request then times out.
         */
        private const val ACK_ECHOES_SEQUENCE = false

        /**
         * Battery sub-types polled on every refresh.
         *
         * DUAL (`0x09`) and CASE (`0x0A`) are the two the XM5 supports; SINGLE (`0x00`) is
         * acknowledged but never answered, and DUAL2 (`0x01`) also works but is redundant.
         */
        private val BATTERY_PROBE_TYPES = listOf(
            SonyProtocol.BATTERY_DUAL,
            SonyProtocol.BATTERY_CASE,
        )
    }

    private class Command(
        val label: String,
        val payload: ByteArray,
        val expectedPayloadTypes: Set<Int>,
        val timeoutMs: Long = REQUEST_TIMEOUT_MS,
        val maxAttempts: Int = 1,
        val onResponse: ((SonyFrame) -> Unit)? = null,
        val onTimeout: (() -> Unit)? = null,
    )

    private val running = AtomicBoolean(false)
    private val commands = LinkedBlockingQueue<Command>()

    private val responseLock = ReentrantLock()
    private val responseSignal = responseLock.newCondition()
    private var responseFrame: SonyFrame? = null

    private val writeLock = Any()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var state = SonyHeadsetState()

    /** Sequence number the host should use for its next request. */
    @Volatile
    private var sequence = 0

    @Volatile
    private var ambientSubtype = SonyProtocol.AMBIENT_SUBTYPE_PLAIN

    /** Set from the headset's own reply, not from the sub-type. See [SonyProtocol.ambientSetRequest]. */
    @Volatile
    private var ambientWindCapable = false

    private var worker: Thread? = null
    private var reader: Thread? = null

    val currentState: SonyHeadsetState get() = state

    private fun notifyConnection(connected: Boolean, message: String?) {
        traffic(
            if (connected) 'i' else '!',
            "connection ${if (connected) "up" else "down"}${message?.let { ": $it" } ?: ""}",
        )
        listener.onConnectionChanged(connected, message)
    }

    /**
     * Report a line of protocol activity. It goes both to the listener (module UI) and to logcat,
     * so the protocol can be verified headlessly with `adb logcat -s XMSound-Sony`.
     */
    private fun traffic(direction: Char, text: String) {
        listener.onTraffic(direction, text)
        when (direction) {
            '>' -> android.util.Log.i(TAG, "TX $text")
            '<' -> android.util.Log.i(TAG, "RX $text")
            else -> android.util.Log.i(TAG, "$direction $text")
        }
    }

    // ---------------------------------------------------------------- public API

    @SuppressLint("MissingPermission")
    fun connect() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runWorker() }, "xmsound-sony-worker").apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        worker?.interrupt()
        reader?.interrupt()
        worker = null
        reader = null
        commands.clear()
        publish(state.copy(connected = false, protocolVersion = 0))
        notifyConnection(false, null)
    }

    fun refresh() {
        batteryProbes().forEach { enqueue(it) }
        enqueue(ambientCommand())
        enqueue(
            Command(
                "firmware",
                SonyProtocol.FIRMWARE_REQUEST,
                setOf(SonyProtocol.PT_FIRMWARE_RET),
            ),
        )
    }

    /**
     * The XM5 is documented as supporting DUAL (0x09) and CASE (0x0A) only. The other two are
     * probed while the protocol mapping is still being verified, because the WF-1000XM5's actual
     * reply payloads are longer than any published reference sample.
     */
    private fun batteryProbes(): List<Command> = BATTERY_PROBE_TYPES.map { type ->
        Command(
            label = "battery-%02x".format(type),
            payload = byteArrayOf(SonyProtocol.PT_BATTERY_GET.toByte(), type.toByte()),
            expectedPayloadTypes = setOf(
                SonyProtocol.PT_BATTERY_RET,
                SonyProtocol.PT_BATTERY_NOTIFY,
            ),
        )
    }

    /**
     * Build the ambient-sound read for [step] of the dialect ladder.
     *
     * Whichever sub-type answers is remembered in [ambientSubtype] and tried first from then on.
     * The retry has to be enqueued from here: the timeout callback fires after the worker has
     * already moved on to the next queued command, so simply flipping a field would never actually
     * re-send the request with the other dialect.
     */
    private fun ambientCommand(step: Int = 0): Command {
        val ladder = ambientLadderOrder()
        val subtype = ladder[step.coerceIn(0, ladder.lastIndex)]
        return Command(
            label = "ambient-%02x".format(subtype),
            payload = SonyProtocol.ambientGetRequest(subtype),
            expectedPayloadTypes = setOf(
                SonyProtocol.PT_AMBIENT_RET,
                SonyProtocol.PT_AMBIENT_NOTIFY,
            ),
            onResponse = { frame -> ambientSubtype = frame.payloadByte(1) },
            onTimeout = {
                val next = step + 1
                if (next <= ladder.lastIndex) {
                    traffic(
                        '!',
                        "ambient 0x%02x unanswered, trying 0x%02x".format(subtype, ladder[next]),
                    )
                    enqueue(ambientCommand(next))
                } else {
                    traffic('!', "ambient: no read dialect answered")
                }
            },
        )
    }

    private fun ambientLadderOrder(): List<Int> {
        val all = SonyProtocol.AMBIENT_SUBTYPE_LADDER
        return if (ambientSubtype in all) {
            listOf(ambientSubtype) + all.filter { it != ambientSubtype }
        } else {
            all
        }
    }

    fun setNoiseMode(mode: SonyNoiseMode, ambientLevel: Int, focusOnVoice: Boolean) {
        val payload = SonyProtocol.ambientSetRequest(
            subtype = ambientSubtype,
            mode = mode,
            ambientLevel = ambientLevel,
            focusOnVoice = focusOnVoice,
            windCapable = ambientWindCapable,
        )
        // The headset answers a SET with an ACK only, so nothing to wait for beyond the write.
        enqueue(Command("set-noise", payload, emptySet(), timeoutMs = 0L))
        // Optimistically reflect the change; the authoritative NOTIFY follows.
        publish(
            state.copy(
                noise = state.noise.copy(
                    mode = mode,
                    ambientLevel = ambientLevel.coerceIn(
                        SonyProtocol.AMBIENT_LEVEL_MIN,
                        SonyProtocol.AMBIENT_LEVEL_MAX,
                    ),
                    focusOnVoice = focusOnVoice,
                ),
            ),
        )
    }

    private fun enqueue(command: Command) {
        if (!running.get()) return
        // Deliberately no Thread.interrupt() here: the worker parks on a short poll instead, so
        // waking it never aborts an in-flight sleep or wait and never looks like a disconnect.
        commands.offer(command)
    }

    // ---------------------------------------------------------------- worker

    @SuppressLint("MissingPermission")
    private fun runWorker() {
        try {
            adapter?.cancelDiscovery()
            Thread.sleep(PRE_CONNECT_DELAY_MS)

            val connectedSocket = openSocket() ?: run {
                notifyConnection(false, "Could not open the Sony control channel")
                return
            }
            socket = connectedSocket
            output = connectedSocket.outputStream

            reader = Thread({ runReader(connectedSocket.inputStream) }, "xmsound-sony-reader").apply {
                isDaemon = true
                start()
            }

            if (!handshake()) {
                notifyConnection(false, "Handshake failed")
                return
            }

            notifyConnection(true, null)
            refresh()

            var lastRefresh = System.currentTimeMillis()
            while (running.get()) {
                val command = try {
                    // Short poll so a freshly enqueued command is picked up promptly and the
                    // periodic refresh deadline is still honoured.
                    commands.poll(WORKER_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }

                if (command != null) {
                    execute(command)
                }

                if (System.currentTimeMillis() - lastRefresh >= REFRESH_INTERVAL_MS) {
                    lastRefresh = System.currentTimeMillis()
                    refresh()
                }
            }
        } catch (t: Throwable) {
            if (running.get()) notifyConnection(false, t.message ?: t.javaClass.simpleName)
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            publish(state.copy(connected = false))
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(): BluetoothSocket? {
        for (uuid in listOf(UUID_V2, UUID_V1)) {
            val candidate = try {
                device.createRfcommSocketToServiceRecord(uuid)
            } catch (t: Throwable) {
                traffic('!', "createRfcommSocket($uuid) failed: ${t.message}")
                continue
            }

            val watchdog = Thread {
                try {
                    Thread.sleep(CONNECT_TIMEOUT_MS)
                    runCatching { candidate.close() }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true }

            try {
                traffic('>', "connect ${device.address} ($uuid)")
                watchdog.start()
                candidate.connect()
                return candidate
            } catch (t: Throwable) {
                runCatching { candidate.close() }
                traffic('!', "connect failed ($uuid): ${t.message}")
            } finally {
                watchdog.interrupt()
            }
        }
        return null
    }

    // ---------------------------------------------------------------- reader

    private fun runReader(input: InputStream) {
        val decoder = SonyFrameDecoder()
        val buffer = ByteArray(2048)
        try {
            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                for (frame in decoder.offer(buffer, 0, read)) handleFrame(frame)
            }
        } catch (t: Throwable) {
            if (running.get()) traffic('!', "reader stopped: ${t.message}")
        } finally {
            if (running.get()) {
                notifyConnection(false, "Control channel closed by the headset")
                running.set(false)
                runCatching { socket?.close() }
            }
        }
    }

    private fun handleFrame(frame: SonyFrame) {
        traffic('<', frame.toHex())

        if (frame.isAck) {
            // The ACK carries the sequence we must use next.
            sequence = frame.sequence and 1
            signalResponse(frame)
            return
        }

        // Any non-ACK frame must be acknowledged before it is processed.
        val ackSequence = if (ACK_ECHOES_SEQUENCE) {
            frame.sequence and 1
        } else {
            1 - (frame.sequence and 1)
        }
        writeFrame(SonyCodec.encode(SonyMessageType.ACK, ackSequence, EMPTY_PAYLOAD))

        applyToState(frame)
        signalResponse(frame)
    }

    private fun signalResponse(frame: SonyFrame) {
        responseLock.lock()
        try {
            responseFrame = frame
            responseSignal.signalAll()
        } finally {
            responseLock.unlock()
        }
    }

    private fun applyToState(frame: SonyFrame) {
        SonyParser.battery(frame)?.let { battery ->
            publish(
                state.copy(
                    battery = state.battery.copy(
                        left = battery.left ?: state.battery.left,
                        right = battery.right ?: state.battery.right,
                        case = battery.case ?: state.battery.case,
                    ),
                ),
            )
            return
        }

        SonyParser.ambient(frame)?.let { noise ->
            ambientSubtype = frame.payloadByte(1)
            ambientWindCapable = SonyParser.ambientUsesWindLayout(frame)
            publish(state.copy(noise = noise, ambientSubtype = ambientSubtype))
            return
        }

        SonyParser.firmware(frame)?.let { version ->
            publish(state.copy(firmware = version))
        }
    }

    // ---------------------------------------------------------------- request/ack

    private fun execute(command: Command) {
        var attempt = 0
        while (attempt < command.maxAttempts && running.get()) {
            attempt++

            responseLock.lock()
            try {
                responseFrame = null
            } finally {
                responseLock.unlock()
            }

            if (!writeFrame(SonyCodec.encode(SonyMessageType.COMMAND_1, sequence, command.payload))) return

            if (command.expectedPayloadTypes.isEmpty() || command.timeoutMs <= 0) {
                // Fire-and-forget SET: give the headset a moment so the next request stays gated.
                try {
                    Thread.sleep(SET_SETTLE_MS)
                } catch (_: InterruptedException) {
                    // Only disconnect() interrupts the worker; the loop condition handles it.
                }
                return
            }

            val response = awaitResponse(command.expectedPayloadTypes, command.timeoutMs)
            if (response != null) {
                command.onResponse?.invoke(response)
                return
            }
            traffic('!', "${command.label} timed out (attempt $attempt)")
        }
        command.onTimeout?.invoke()
    }

    private fun awaitResponse(expected: Set<Int>, timeoutMs: Long): SonyFrame? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        responseLock.lock()
        try {
            while (true) {
                val frame = responseFrame
                if (frame != null && frame.payloadType in expected) {
                    responseFrame = null
                    return frame
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                responseSignal.await(remaining, TimeUnit.NANOSECONDS)
            }
        } catch (_: InterruptedException) {
            return null
        } finally {
            responseLock.unlock()
        }
    }

    private fun writeFrame(frame: ByteArray): Boolean {
        val stream = output ?: return false
        return try {
            synchronized(writeLock) {
                stream.write(frame)
                stream.flush()
            }
            traffic('>', frame.toHex())
            true
        } catch (t: Throwable) {
            traffic('!', "write failed: ${t.message}")
            throw IOException("Sony control channel write failed", t)
        }
    }

    // ---------------------------------------------------------------- handshake

    private fun handshake(): Boolean {
        var attempt = 0
        while (attempt < INIT_MAX_ATTEMPTS && running.get()) {
            attempt++

            responseLock.lock()
            try {
                responseFrame = null
            } finally {
                responseLock.unlock()
            }

            writeFrame(SonyCodec.encode(SonyMessageType.COMMAND_1, 0, SonyProtocol.INIT_REQUEST))
            val response = awaitResponse(
                setOf(SonyProtocol.PT_INIT_REPLY),
                INIT_TIMEOUT_MS,
            ) ?: continue

            val version = SonyParser.initReplyVersion(response) ?: continue
            // INIT went out with sequence 0, so the device ACKs with 1 and expects 1 next. Adopting
            // the ACK already produced that value; re-assert it because a unit that skips the ACK
            // would otherwise make the first real request look like a retransmission (and be dropped).
            sequence = 1
            publish(state.copy(connected = true, protocolVersion = version))
            traffic('i', "handshake ok, protocol v$version")
            return true
        }
        return false
    }

    private fun publish(newState: SonyHeadsetState) {
        state = newState
        traffic('s', "state $newState")
        listener.onStateChanged(newState)
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
