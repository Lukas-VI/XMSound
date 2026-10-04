package moe.yanhe.xmsound.pods.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import moe.yanhe.xmsound.ui.AppPrefs

/** One line of the on-the-wire protocol log. */
data class TrafficLine(
    val direction: Char,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Process-wide owner of the Sony session.
 *
 * Both the module UI and (later) the hooked system processes talk to the headset through this
 * object, so there is at most one RFCOMM channel and one reader per device address - the headset
 * does not accept two SPP clients.
 */
class SonyHeadsetController private constructor(private val context: Context) {

    interface Observer {
        fun onStateChanged(state: SonyHeadsetState) {}
        fun onConnectionChanged(connected: Boolean, message: String?) {}
        fun onTraffic(line: TrafficLine) {}
    }

    companion object {
        private const val PREFS = "xmsound_state"
        private const val KEY_DEVICE = "device_address"
        private const val TRAFFIC_LIMIT = 500

        /** Names that identify a Sony headset when no explicit address has been stored yet. */
        private val SONY_NAME_PREFIXES = listOf("WF-", "WH-", "WI-", "MDR-", "LinkBuds", "Sony")

        /** Preferred target; the WF-1000XM5 is what this module is built for. */
        private const val PREFERRED_MODEL = "WF-1000XM5"

        /** Logcat tag for the app-side session, readable with `adb logcat -s XMSound-App`. */
        const val TAG = "XMSound-App"

        /**
         * The headset drops the SPP link when it is idle, and any other control app (Sony Sound
         * Connect, HyperOS' Fusion Device Center) that grabs the single SPP client slot takes it
         * away from us. Reconnecting is therefore normal operation, not error handling.
         */
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

        @Volatile
        private var instance: SonyHeadsetController? = null

        fun get(context: Context): SonyHeadsetController =
            instance ?: synchronized(this) {
                instance ?: SonyHeadsetController(context.applicationContext).also { instance = it }
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()
    private val trafficLog = ArrayDeque<TrafficLine>()

    @Volatile
    private var session: SonyHeadsetSession? = null

    @Volatile
    var state: SonyHeadsetState = SonyHeadsetState()
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var selectedDevice: BluetoothDevice? = null
        private set

    @Volatile
    private var userRequestedDisconnect = false

    @Volatile
    private var reconnectAttempts = 0

    /**
     * Incremented by [requestIsland]. The Bluetooth-process hook compares it against the value it
     * last saw and raises the native popup when it changes, which is how the popup is triggered on
     * demand (and tested).
     */
    @Volatile
    var islandRequestSeq: Int = 0
        private set

    fun requestIsland() {
        islandRequestSeq++
    }

    private val reconnectRunnable = Runnable {
        if (!userRequestedDisconnect && !isConnected && session == null) connect()
    }

    val isConnected: Boolean get() = state.connected

    /** Snapshot of the protocol log, oldest first. */
    fun traffic(): List<TrafficLine> = synchronized(trafficLog) { trafficLog.toList() }

    fun clearTraffic() {
        synchronized(trafficLog) { trafficLog.clear() }
        post { observers.forEach { it.onTraffic(TrafficLine('i', "log cleared")) } }
    }

    fun addObserver(observer: Observer) {
        observers.addIfAbsent(observer)
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ------------------------------------------------------------ device selection

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Bonded devices that look like a Sony headset, best match first. The stored/again preferred
     * model wins, then any Sony-prefixed name, then everything else so the user can still pick.
     */
    @SuppressLint("MissingPermission")
    fun candidateDevices(): List<BluetoothDevice> {
        val bonded = runCatching { adapter?.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())
        return bonded.sortedBy { device ->
            val name = runCatching { device.name }.getOrNull().orEmpty()
            when {
                name.contains(PREFERRED_MODEL, ignoreCase = true) -> 0
                SONY_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) } -> 1
                else -> 2
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveDevice(): BluetoothDevice? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE, null)
        if (stored != null) {
            runCatching { adapter?.getRemoteDevice(stored) }.getOrNull()?.let { return it }
        }
        return candidateDevices().firstOrNull()
    }

    fun selectDevice(device: BluetoothDevice) {
        selectedDevice = device
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE, device.address)
            .apply()
    }

    // ------------------------------------------------------------ lifecycle

    /** Connect (or reconnect) to the selected headset. Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    fun connect() {
        userRequestedDisconnect = false
        mainHandler.removeCallbacks(reconnectRunnable)

        if (isConnected) return
        if (session != null) return

        val device = selectedDevice ?: resolveDevice()
        if (device == null) {
            lastError = "No bonded headset found"
            post { observers.forEach { it.onConnectionChanged(false, lastError) } }
            return
        }
        selectedDevice = device

        lastError = null
        val newSession = SonyHeadsetSession(device, adapter, sessionListener)
        session = newSession
        newSession.connect()
    }

    fun disconnect() {
        userRequestedDisconnect = true
        mainHandler.removeCallbacks(reconnectRunnable)
        session?.disconnect()
        session = null
        state = state.copy(connected = false, protocolVersion = 0)
        HeadsetNotificationCard.cancel(context)
        post { observers.forEach { it.onStateChanged(state) } }
    }

    private fun scheduleReconnect() {
        if (userRequestedDisconnect) return
        val delay = RECONNECT_DELAYS_MS[minOf(reconnectAttempts, RECONNECT_DELAYS_MS.lastIndex)]
        reconnectAttempts++
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    /** True when Bluetooth is on and the runtime permission has been granted. */
    @SuppressLint("MissingPermission")
    fun isBluetoothReady(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    fun refresh() {
        session?.refresh()
    }

    // ------------------------------------------------------------ controls

    fun setNoiseMode(mode: SonyNoiseMode) {
        val current = state.noise
        session?.setNoiseMode(mode, current.ambientLevel, current.focusOnVoice)
    }

    fun setAmbientLevel(level: Int) {
        val current = state.noise
        val mode = if (current.mode == SonyNoiseMode.AMBIENT_SOUND) {
            SonyNoiseMode.AMBIENT_SOUND
        } else {
            current.mode
        }
        session?.setNoiseMode(mode, level, current.focusOnVoice)
    }

    fun setFocusOnVoice(enabled: Boolean) {
        val current = state.noise
        session?.setNoiseMode(current.mode, current.ambientLevel, enabled)
    }

    fun cycleNoiseMode() {
        val next = when (state.noise.mode) {
            SonyNoiseMode.NOISE_CANCELLING -> SonyNoiseMode.AMBIENT_SOUND
            SonyNoiseMode.AMBIENT_SOUND -> SonyNoiseMode.OFF
            else -> SonyNoiseMode.NOISE_CANCELLING
        }
        setNoiseMode(next)
    }

    // ------------------------------------------------------------ system surfaces

    /**
     * Mirror the state onto the module-owned HyperOS surface: the ongoing notification card.
     *
     * The island / connection popup is not shown from here - it is a strong toast driven from the
     * Bluetooth process, which is the only place holding the `STATUS_BAR` permission.
     */
    private fun publishToSystem(state: SonyHeadsetState) {
        runCatching {
            val name = runCatching { selectedDevice?.name }.getOrNull()

            if (AppPrefs.notificationCardEnabled(context) && state.connected) {
                HeadsetNotificationCard.update(context, state, name)
            } else {
                HeadsetNotificationCard.cancel(context)
            }
        }.onFailure {
            // Never let a notification problem break the session.
        }
    }

    // ------------------------------------------------------------ session plumbing

    private val sessionListener = object : SonyHeadsetSession.Listener {
        override fun onStateChanged(state: SonyHeadsetState) {
            this@SonyHeadsetController.state = state
            publishToSystem(state)
            post { observers.forEach { it.onStateChanged(state) } }
        }

        override fun onConnectionChanged(connected: Boolean, message: String?) {
            if (message != null) lastError = message
            if (connected) {
                reconnectAttempts = 0
            } else {
                session = null
                this@SonyHeadsetController.state =
                    this@SonyHeadsetController.state.copy(connected = false, protocolVersion = 0)
                scheduleReconnect()
            }
            post {
                observers.forEach { it.onConnectionChanged(connected, message) }
                observers.forEach { it.onStateChanged(this@SonyHeadsetController.state) }
            }
        }

        override fun onTraffic(direction: Char, text: String) {
            val line = TrafficLine(direction, text)
            synchronized(trafficLog) {
                trafficLog.addLast(line)
                while (trafficLog.size > TRAFFIC_LIMIT) trafficLog.removeFirst()
            }
            post { observers.forEach { it.onTraffic(line) } }
        }
    }
}
