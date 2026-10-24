package moe.yanhe.xmsound.hook.runtime

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.atomic.AtomicBoolean
import moe.yanhe.xmsound.hook.HeadsetStateCache
import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.pods.sony.HeadsetNotificationCard
import moe.yanhe.xmsound.pods.sony.SonyBridge
import moe.yanhe.xmsound.pods.sony.SonyHeadsetController
import moe.yanhe.xmsound.pods.sony.SonyHeadsetState
import moe.yanhe.xmsound.pods.sony.SonyStateBundle

/**
 * Hosts the Sony SPP session inside a hooked system process.
 *
 * This is the fix for the module only working while its own process was alive. The session used to
 * live in the module process, so clearing the module from recents dropped the RFCOMM channel and
 * every spoofed value went stale. `com.android.bluetooth` is started by the platform at boot and is
 * never killed, so hosting the session here makes the module independent of its own UI.
 *
 * The module process is reduced to a client: it receives state broadcasts and sends commands. It is
 * not needed for the headset to stay connected - and neither is the notification card, which this
 * process posts as well (see [HeadsetNotificationCard]).
 */
object SonyRuntime {

    private const val TAG = "Runtime"

    /** Settings live in the host process, because the module's preferences are not readable here. */
    private const val PREFS = "xmsound_runtime"
    private const val KEY_ISLAND_ENABLED = "islandEnabled"
    private const val KEY_CARD_ENABLED = "cardEnabled"

    /** ~30s of patience for the host Application to appear. */
    private const val BOOTSTRAP_ATTEMPTS = 120
    private const val BOOTSTRAP_INTERVAL_MS = 250L

    private val started = AtomicBoolean(false)

    @Volatile
    private var ownerController: SonyHeadsetController? = null

    @Volatile
    private var ownerPrefs: android.content.SharedPreferences? = null

    /**
     * The last bundle that went out. Unchanged state is not re-broadcast: the 30s poll cycle
     * produces identical frames, and delivering them again would wake every hooked process for
     * nothing. An explicit [SonyBridge.ACTION_REQUEST_STATE] always forces a fresh broadcast.
     */
    @Volatile
    private var lastBroadcast: android.os.Bundle? = null

    /** True in the process that owns the session; false everywhere else. */
    @Volatile
    var isOwner: Boolean = false
        private set

    /**
     * The live state when this process owns the session, null otherwise.
     *
     * Lets the spoofing hooks in this process read the controller directly instead of waiting for
     * their own broadcast to come back.
     */
    fun liveBundle(): android.os.Bundle? {
        if (!isOwner) return null
        val controller = ownerController ?: return null
        val prefs = ownerPrefs ?: return null
        return encode(controller, prefs)
    }

    /** Start the session owner. Idempotent; safe to call from any hook in this process. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val app = context.applicationContext ?: context
        val controller = SonyHeadsetController.get(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ownerController = controller
        ownerPrefs = prefs
        isOwner = true

        controller.statePublisher = { state, error ->
            publishState(app, controller, state, error)
        }

        registerCommandReceiver(app, controller, prefs)
        registerLinkReceiver(app, controller)

        HookLog.i(TAG, "session owner started in ${app.packageName}")
        // The headset is a paired device, so there is nothing to wait for - connect immediately and
        // let the controller's own backoff handle the fact that SPP has a single client slot.
        controller.connect()
    }

    /**
     * Start once the host Application exists.
     *
     * `onPackageLoaded` runs before `Application.onCreate`, so there is no context yet; rather than
     * depend on a particular class being touched, poll briefly for the application and then start.
     * The Bluetooth process creates its Application within milliseconds of being loaded.
     */
    fun bootstrap() {
        Thread({
            repeat(BOOTSTRAP_ATTEMPTS) {
                val context = HeadsetStateCache.appContext()
                if (context != null) {
                    start(context)
                    return@Thread
                }
                runCatching { Thread.sleep(BOOTSTRAP_INTERVAL_MS) }
            }
            HookLog.w(TAG, "no application context yet; session owner not started")
        }, "xmsound-runtime-bootstrap").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------ owner -> clients

    private fun encode(
        controller: SonyHeadsetController,
        prefs: android.content.SharedPreferences,
    ): android.os.Bundle = SonyStateBundle.encode(
        state = controller.state,
        address = controller.selectedDevice?.address,
        deviceName = runCatching { controller.selectedDevice?.name }.getOrNull(),
        islandEnabled = prefs.getBoolean(KEY_ISLAND_ENABLED, true),
        cardEnabled = prefs.getBoolean(KEY_CARD_ENABLED, true),
        islandRequest = controller.islandRequestSeq,
        error = controller.lastError,
    )

    private fun publishState(
        context: Context,
        controller: SonyHeadsetController,
        state: SonyHeadsetState,
        error: String?,
    ) {
        val prefs = ownerPrefs ?: return
        val bundle = encode(controller, prefs)

        if (bundle != lastBroadcast) {
            lastBroadcast = bundle
            SonyBridge.STATE_TARGETS.forEach { target ->
                send(context, Intent(SonyBridge.ACTION_STATE).putExtra(SonyBridge.EXTRA_STATE, bundle), target)
            }
        }

        // The card follows the state pipeline so it keeps working when the module process is dead.
        if (prefs.getBoolean(KEY_CARD_ENABLED, true) && state.connected) {
            HeadsetNotificationCard.update(
                context,
                state,
                runCatching { controller.selectedDevice?.name }.getOrNull(),
            )
        } else {
            HeadsetNotificationCard.cancel(context)
        }
    }

    private fun broadcastTraffic(context: Context, controller: SonyHeadsetController) {
        val lines = controller.traffic().map { "${it.direction}${it.text}" }.toTypedArray()
        send(
            context,
            Intent(SonyBridge.ACTION_TRAFFIC).putExtra(SonyBridge.EXTRA_LINES, lines),
            SonyBridge.MODULE_PACKAGE,
        )
    }

    /**
     * Address the broadcast to a single package so unrelated apps never see headset state. The
     * receiver is registered dynamically, so a stopped package simply misses it and asks for a
     * snapshot when it starts.
     */
    private fun send(context: Context, intent: Intent, target: String) {
        runCatching { context.sendBroadcast(intent.setPackage(target)) }
            .onFailure { HookLog.w(TAG, "broadcast to $target failed: ${it.message}") }
    }

    // ------------------------------------------------------------ link events -> owner

    /**
     * Drive reconnection from real Bluetooth events instead of a fixed retry ladder:
     *
     *  - adapter off pauses the retries entirely (nothing can succeed until it is back),
     *  - adapter on / the target's ACL link coming up reconnects immediately - which is also the
     *    moment a WF-1000XM5 actually becomes reachable, e.g. when the case is opened.
     *
     * Together with a longer backoff ceiling this replaces hammering an unreachable headset every
     * 30 seconds forever, which was pure battery drain for both phones.
     */
    private fun registerLinkReceiver(context: Context, controller: SonyHeadsetController) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                            BluetoothAdapter.STATE_ON
                        controller.onAdapterStateChanged(on)
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                        if (device != null) controller.onDeviceAclConnected(device)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        runCatching {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }.onFailure {
            HookLog.w(TAG, "link receiver not registered: ${it.message}")
        }
    }

    // ------------------------------------------------------------ clients -> owner

    private fun registerCommandReceiver(
        context: Context,
        controller: SonyHeadsetController,
        prefs: android.content.SharedPreferences,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                runCatching { handle(action, intent, controller, prefs, context) }
                    .onFailure { HookLog.w(TAG, "$action failed: ${it.message}") }
            }
        }

        val filter = IntentFilter().apply { SonyBridge.COMMAND_ACTIONS.forEach { addAction(it) } }
        runCatching {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }.onFailure {
            HookLog.w(TAG, "command receiver not registered: ${it.message}")
        }
    }

    private fun handle(
        action: String,
        intent: Intent,
        controller: SonyHeadsetController,
        prefs: android.content.SharedPreferences,
        context: Context,
    ) {
        when (action) {
            SonyBridge.ACTION_CONNECT -> {
                // An explicit address means the user picked a device in the module UI.
                intent.getStringExtra(SonyBridge.EXTRA_ADDRESS)?.let { picked ->
                    runCatching {
                        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                            as? android.bluetooth.BluetoothManager)?.adapter
                        adapter?.getRemoteDevice(picked)?.let { controller.selectDevice(it) }
                    }.onFailure { HookLog.w(TAG, "unknown address $picked") }
                }
                controller.connect()
            }
            SonyBridge.ACTION_DISCONNECT -> controller.disconnect()
            SonyBridge.ACTION_RECONNECT -> controller.reconnect()
            SonyBridge.ACTION_REFRESH -> controller.refresh()
            SonyBridge.ACTION_CYCLE_NOISE -> controller.cycleNoiseMode()
            SonyBridge.ACTION_SHOW_ISLAND -> controller.requestIsland()

            SonyBridge.ACTION_SHOW_CARD -> HeadsetNotificationCard.force(
                context,
                controller.state,
                runCatching { controller.selectedDevice?.name }.getOrNull(),
            )

            SonyBridge.ACTION_SET_NOISE -> {
                val name = intent.getStringExtra(SonyBridge.EXTRA_MODE)
                val mode = SonyBridge.parseMode(name)
                if (mode == null) {
                    HookLog.w(TAG, "unknown noise mode '$name'")
                    return
                }
                controller.setNoiseMode(mode)
            }

            SonyBridge.ACTION_SET_AMBIENT_LEVEL ->
                controller.setAmbientLevel(intent.getIntExtra(SonyBridge.EXTRA_LEVEL, 10))

            SonyBridge.ACTION_SET_FOCUS_ON_VOICE ->
                controller.setFocusOnVoice(intent.getBooleanExtra(SonyBridge.EXTRA_ENABLED, false))

            SonyBridge.ACTION_APPLY_SETTINGS -> {
                prefs.edit()
                    .putBoolean(KEY_ISLAND_ENABLED, intent.getBooleanExtra(SonyBridge.EXTRA_ISLAND_ENABLED, true))
                    .putBoolean(KEY_CARD_ENABLED, intent.getBooleanExtra(SonyBridge.EXTRA_CARD_ENABLED, true))
                    .apply()
                HookLog.i(TAG, "island=${intent.getBooleanExtra(SonyBridge.EXTRA_ISLAND_ENABLED, true)} card=${intent.getBooleanExtra(SonyBridge.EXTRA_CARD_ENABLED, true)}")
                // Re-evaluate immediately, so toggling the card does not wait for the next state.
                publishState(context, controller, controller.state, controller.lastError)
            }

            // Broadcasts are not sticky, so a process that started late asks for a snapshot. This
            // one bypasses the dedupe by design.
            SonyBridge.ACTION_REQUEST_STATE -> {
                val bundle = encode(controller, prefs)
                lastBroadcast = bundle
                SonyBridge.STATE_TARGETS.forEach { target ->
                    send(context, Intent(SonyBridge.ACTION_STATE).putExtra(SonyBridge.EXTRA_STATE, bundle), target)
                }
            }

            SonyBridge.ACTION_REQUEST_TRAFFIC -> broadcastTraffic(context, controller)
        }
    }
}
