package moe.yanhe.xmsound.hook.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.atomic.AtomicBoolean
import moe.yanhe.xmsound.hook.HeadsetStateCache
import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.pods.sony.SonyBridge
import moe.yanhe.xmsound.pods.sony.SonyHeadsetController
import moe.yanhe.xmsound.pods.sony.SonyHeadsetState
import moe.yanhe.xmsound.pods.sony.SonyNoiseMode
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
 * not needed for the headset to stay connected.
 */
object SonyRuntime {

    private const val TAG = "Runtime"

    /** Settings live in the host process, because the module's preferences are not readable here. */
    private const val PREFS = "xmsound_runtime"
    private const val KEY_ISLAND_ENABLED = "islandEnabled"

    /** ~30s of patience for the host Application to appear. */
    private const val BOOTSTRAP_ATTEMPTS = 120
    private const val BOOTSTRAP_INTERVAL_MS = 250L

    private val started = AtomicBoolean(false)

    @Volatile
    private var ownerController: SonyHeadsetController? = null

    @Volatile
    private var ownerPrefs: android.content.SharedPreferences? = null

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
        return SonyStateBundle.encode(
            state = controller.state,
            address = controller.selectedDevice?.address,
            deviceName = runCatching { controller.selectedDevice?.name }.getOrNull(),
            islandEnabled = prefs.getBoolean(KEY_ISLAND_ENABLED, true),
            islandRequest = controller.islandRequestSeq,
            error = controller.lastError,
        )
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
            broadcastState(app, controller, state, error, prefs.getBoolean(KEY_ISLAND_ENABLED, true))
        }

        registerCommandReceiver(app, controller, prefs)

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

    private fun broadcastState(
        context: Context,
        controller: SonyHeadsetController,
        state: SonyHeadsetState,
        error: String?,
        islandEnabled: Boolean,
    ) {
        val bundle = SonyStateBundle.encode(
            state = state,
            address = controller.selectedDevice?.address,
            deviceName = runCatching { controller.selectedDevice?.name }.getOrNull(),
            islandEnabled = islandEnabled,
            islandRequest = controller.islandRequestSeq,
            error = error,
        )
        SonyBridge.STATE_TARGETS.forEach { target ->
            send(context, Intent(SonyBridge.ACTION_STATE).putExtra(SonyBridge.EXTRA_STATE, bundle), target)
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
                val enabled = intent.getBooleanExtra(SonyBridge.EXTRA_ISLAND_ENABLED, true)
                prefs.edit().putBoolean(KEY_ISLAND_ENABLED, enabled).apply()
                HookLog.i(TAG, "island enabled=$enabled")
            }

            SonyBridge.ACTION_REQUEST_STATE ->
                broadcastState(
                    context,
                    controller,
                    controller.state,
                    controller.lastError,
                    prefs.getBoolean(KEY_ISLAND_ENABLED, true),
                )

            SonyBridge.ACTION_REQUEST_TRAFFIC -> broadcastTraffic(context, controller)
        }
    }
}
