package moe.yanhe.xmsound.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import moe.yanhe.xmsound.hook.runtime.SonyRuntime
import moe.yanhe.xmsound.pods.sony.SonyBridge

/**
 * The headset state as seen by the spoofing hooks.
 *
 * Fed by broadcasts from the process that owns the session ([SonyRuntime]), never by reading the
 * module process. It used to poll a `ContentProvider` in the module, which had two problems: every
 * getter risked a synchronous binder round trip on the UI thread, and - because content providers are
 * started on demand - the polling kept resurrecting the module process, so the module appeared to
 * have to stay in the background to keep working.
 *
 * In the owning process this forwards straight to the live controller, so there is no broadcast hop
 * there.
 */
object HeadsetStateCache {

    private val started = AtomicBoolean(false)

    @Volatile
    private var cached: Bundle? = null

    /** The host application, resolved once. `ActivityThread.currentApplication()` is all we have. */
    @Volatile
    private var appContextCache: Context? = null

    fun appContext(): Context? {
        appContextCache?.let { return it }
        val resolved = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()
        if (resolved != null) appContextCache = resolved
        return resolved
    }

    /** Subscribe to state broadcasts. Idempotent; safe to call from any hook. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContextCache = context.applicationContext ?: context

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val bundle = intent?.getBundleExtra(SonyBridge.EXTRA_STATE) ?: return
                cached = bundle
            }
        }
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(SonyBridge.ACTION_STATE),
                Context.RECEIVER_EXPORTED,
            )
            HookLog.i("StateCache", "subscribed in ${context.packageName}")
        }.onFailure {
            HookLog.w("StateCache", "subscribe failed: ${it.message}")
        }
    }

    /**
     * Latest state, or null before the first broadcast arrives. Never blocks.
     *
     * Subscribes on first use if the caller did not, so a hook that only ever calls `get()` still
     * receives state - without that, such a hook silently saw nothing forever.
     */
    fun get(): Bundle? {
        SonyRuntime.liveBundle()?.let { return it }
        appContext()?.let { start(it) }
        return cached
    }

    /**
     * Ask the owner for a snapshot.
     *
     * Broadcasts are not sticky, so a process that started after the session did has no state until
     * either the next change or an explicit request.
     */
    fun requestRefresh() {
        val context = appContext() ?: return
        runCatching {
            context.sendBroadcast(
                Intent(SonyBridge.ACTION_REQUEST_STATE).setPackage(SonyBridge.OWNER_PACKAGE),
            )
        }
    }
}
