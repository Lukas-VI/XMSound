package moe.yanhe.xmsound.pods.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import moe.yanhe.xmsound.ui.AppPrefs

/**
 * The module process's view of the headset.
 *
 * Deliberately mirrors [SonyHeadsetController]'s API so the UI did not have to change, but this class
 * owns **no** connection: the session lives in the Bluetooth process ([SonyRuntime]). Clearing the
 * module from recents therefore no longer drops the headset - the UI simply detaches.
 *
 * Commands are broadcasts to the owner; state comes back the same way.
 */
class SonyRemoteController private constructor(private val context: Context) {

    /** Same shape as the owner's observer so `HeadsetUiState` works with either. */
    interface Observer {
        fun onStateChanged(state: SonyHeadsetState) {}
        fun onConnectionChanged(connected: Boolean, message: String?) {}
        fun onTraffic(line: TrafficLine) {}
    }

    companion object {
        private const val TRAFFIC_POLL_MS = 1_000L
        private const val TRAFFIC_LIMIT = 500

        /** Names that identify a Sony headset when ranking the paired devices. */
        private val SONY_NAME_PREFIXES = listOf("WF-", "WH-", "WI-", "MDR-", "LinkBuds", "Sony")

        @Volatile
        private var instance: SonyRemoteController? = null

        fun get(context: Context): SonyRemoteController =
            instance ?: synchronized(this) {
                instance ?: SonyRemoteController(context.applicationContext).also { instance = it }
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()
    private val trafficLog = ArrayDeque<TrafficLine>()
    private val bondedAddresses = CopyOnWriteArrayList<String>()

    @Volatile
    var state: SonyHeadsetState = SonyHeadsetState()
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var address: String? = null

    @Volatile
    private var deviceName: String? = null

    val islandRequestSeq: Int get() = 0

    val isConnected: Boolean get() = state.connected

    val selectedDevice: BluetoothDevice?
        @SuppressLint("MissingPermission")
        get() = address?.let { runCatching { adapter?.getRemoteDevice(it) }.getOrNull() }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    init {
        registerReceiver()
        startTrafficPump()
        requestState()
    }

    // ------------------------------------------------------------ state in

    private fun registerReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    SonyBridge.ACTION_STATE -> onStateBundle(intent)
                    SonyBridge.ACTION_TRAFFIC -> onTrafficDump(intent)
                }
            }
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(SonyBridge.ACTION_STATE)
                addAction(SonyBridge.ACTION_TRAFFIC)
            }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }
    }

    private fun onStateBundle(intent: Intent) {
        val bundle = intent.getBundleExtra(SonyBridge.EXTRA_STATE) ?: return
        val decoded = SonyStateBundle.decode(bundle)

        address = bundle.getString(SonyStateBundle.KEY_ADDRESS)
        deviceName = bundle.getString(SonyStateBundle.KEY_DEVICE_NAME)
        lastError = bundle.getString(SonyStateBundle.KEY_ERROR)

        val wasConnected = state.connected
        state = decoded
        publishToSystem(decoded)

        post {
            if (wasConnected != decoded.connected) {
                observers.forEach { it.onConnectionChanged(decoded.connected, lastError) }
            }
            observers.forEach { it.onStateChanged(decoded) }
        }
    }

    private fun onTrafficDump(intent: Intent) {
        val lines = intent.getStringArrayExtra(SonyBridge.EXTRA_LINES) ?: return
        synchronized(trafficLog) {
            trafficLog.clear()
            lines.forEach { line ->
                if (line.isNotEmpty()) {
                    trafficLog.addLast(TrafficLine(line[0], line.substring(1)))
                }
            }
            while (trafficLog.size > TRAFFIC_LIMIT) trafficLog.removeFirst()
        }
        post { observers.forEach { it.onTraffic(TrafficLine('i', "log refreshed")) } }
    }

    private fun publishToSystem(current: SonyHeadsetState) {
        runCatching {
            if (AppPrefs.notificationCardEnabled(context) && current.connected) {
                HeadsetNotificationCard.update(context, current, deviceName)
            } else {
                HeadsetNotificationCard.cancel(context)
            }
        }.onFailure {
            // A notification problem must never break the UI.
        }
    }

    // ------------------------------------------------------------ observers

    fun addObserver(observer: Observer) {
        observers.addIfAbsent(observer)
        post { observer.onStateChanged(state) }
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Snapshot of the protocol log, oldest first. */
    fun traffic(): List<TrafficLine> = synchronized(trafficLog) { trafficLog.toList() }

    fun clearTraffic() {
        synchronized(trafficLog) { trafficLog.clear() }
        post { observers.forEach { it.onTraffic(TrafficLine('i', "log cleared")) } }
    }

    /**
     * Traffic is relayed on demand rather than pushed, so a module that is not open costs nothing.
     * The dump is only requested while something is actually watching.
     */
    private fun startTrafficPump() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (observers.isNotEmpty()) {
                    // Only the details page renders the log; refresh it while it is on screen.
                    send(SonyBridge.ACTION_REQUEST_TRAFFIC)
                }
                mainHandler.postDelayed(this, TRAFFIC_POLL_MS)
            }
        }, TRAFFIC_POLL_MS)
    }

    // ------------------------------------------------------------ commands out

    private fun send(action: String, configure: (Intent) -> Unit = {}) {
        runCatching {
            val intent = Intent(action).setPackage(SonyBridge.OWNER_PACKAGE)
            configure(intent)
            context.sendBroadcast(intent)
        }
    }

    fun requestState() = send(SonyBridge.ACTION_REQUEST_STATE)

    fun connect() = send(SonyBridge.ACTION_CONNECT)

    fun disconnect() = send(SonyBridge.ACTION_DISCONNECT)

    fun reconnect() = send(SonyBridge.ACTION_RECONNECT)

    fun refresh() = send(SonyBridge.ACTION_REFRESH)

    fun requestIsland() = send(SonyBridge.ACTION_SHOW_ISLAND)

    fun setNoiseMode(mode: SonyNoiseMode) =
        send(SonyBridge.ACTION_SET_NOISE) { it.putExtra(SonyBridge.EXTRA_MODE, mode.name) }

    fun setAmbientLevel(level: Int) =
        send(SonyBridge.ACTION_SET_AMBIENT_LEVEL) { it.putExtra(SonyBridge.EXTRA_LEVEL, level) }

    fun setFocusOnVoice(enabled: Boolean) =
        send(SonyBridge.ACTION_SET_FOCUS_ON_VOICE) { it.putExtra(SonyBridge.EXTRA_ENABLED, enabled) }

    fun cycleNoiseMode() = send(SonyBridge.ACTION_CYCLE_NOISE)

    /** Tell the owner about the module's notification preferences. */
    fun applySettings() =
        send(SonyBridge.ACTION_APPLY_SETTINGS) {
            it.putExtra(SonyBridge.EXTRA_ISLAND_ENABLED, AppPrefs.islandEnabled(context))
        }

    @SuppressLint("MissingPermission")
    fun isBluetoothReady(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    // ------------------------------------------------------------ device picker

    @SuppressLint("MissingPermission")
    fun candidateDevices(): List<BluetoothDevice> {
        val bonded = runCatching { adapter?.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())
        bondedAddresses.clear()
        bonded.forEach { bondedAddresses.add(it.address) }
        return bonded.sortedBy { device ->
            val name = runCatching { device.name }.getOrNull().orEmpty()
            when {
                name.contains(SonyStateBundle.MODEL, ignoreCase = true) -> 0
                SONY_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) } -> 1
                else -> 2
            }
        }
    }

    /**
     * Selecting only records the address: the owner resolves and connects, so the UI and the session
     * cannot disagree about which device is in use.
     */
    fun selectDevice(device: BluetoothDevice) {
        address = device.address
        deviceName = runCatching { device.name }.getOrNull()
        send(SonyBridge.ACTION_CONNECT) { it.putExtra(SonyBridge.EXTRA_ADDRESS, device.address) }
    }
}
