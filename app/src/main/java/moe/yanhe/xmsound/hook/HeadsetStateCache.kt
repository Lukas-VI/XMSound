package moe.yanhe.xmsound.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Caches the headset state inside each hooked process.
 *
 * The values live in the module's own process, behind a ContentProvider. Reading them directly from
 * a hook is a synchronous binder round trip, and HyperOS calls the getters **on the main thread**
 * while laying out the UI - doing that per call froze the Fusion Device Center.
 *
 * So a single daemon thread refreshes a snapshot on a timer and on demand, and every getter answers
 * from memory.
 */
object HeadsetStateCache {

    private const val REFRESH_MS = 2_000L
    private const val AUTHORITY = "moe.yanhe.xmsound.state"

    @Volatile
    private var cached: Bundle? = null

    private val started = AtomicBoolean(false)

    /** Single worker for on-demand refreshes, so callers never wait on a binder themselves. */
    private val refresher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xmsound-state-refresh").apply { isDaemon = true }
    }

    /** Starts the background refresher once per process. Safe to call from any hook. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            while (true) {
                refresh()
                runCatching { Thread.sleep(REFRESH_MS) }
            }
        }, "xmsound-state-cache").apply { isDaemon = true }.start()
    }

    /** Latest snapshot; never blocks on a binder once the cache is warm. */
    fun get(): Bundle? {
        val current = cached
        if (current != null) return current
        refresh()
        return cached
    }

    /**
     * Ask for a refresh without blocking the caller. Used right after a command, where the caller
     * may be on the UI thread.
     */
    fun refreshAsync() {
        runCatching { refresher.execute { refresh() } }
    }

    /** Refresh immediately, e.g. right after a command so the next read is up to date. */
    fun refresh() {
        val context = appContext() ?: return
        runCatching {
            context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "state", null, null)
        }.getOrNull()?.let { cached = it }
    }

    /**
     * Our own process, resolved once. `ActivityThread.currentApplication()` is the only context
     * available before any hooked object has been seen.
     */
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
}
