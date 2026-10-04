package moe.yanhe.xmsound.hook

import io.github.libxposed.api.XposedModule

/**
 * Level-gated logging for the hook layer.
 *
 * Every line is written twice: through [XposedModule.log] (so it shows up in the LSPosed manager's
 * log viewer, which is where a user looks) **and** through [android.util.Log] (so it is visible to
 * `adb logcat -s XMSound-Hook` without root and without the manager UI). The second path is what
 * makes the module verifiable headlessly.
 */
object HookLog {
    const val LOG_LEVEL_OFF = 0
    const val LOG_LEVEL_BASIC = 1
    const val LOG_LEVEL_DEBUG = 2

    /** Logcat tag; `adb logcat -s XMSound-Hook:*` shows everything the hook layer does. */
    const val LOGCAT_TAG = "XMSound-Hook"

    @Volatile
    var module: XposedModule? = null

    @Volatile
    var level: Int = LOG_LEVEL_DEBUG

    private fun emit(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        val line = "[$tag] $message"
        runCatching {
            when (priority) {
                android.util.Log.ERROR -> android.util.Log.e(LOGCAT_TAG, line, throwable)
                android.util.Log.WARN -> android.util.Log.w(LOGCAT_TAG, line, throwable)
                else -> android.util.Log.i(LOGCAT_TAG, line, throwable)
            }
        }
        runCatching {
            val framework = module ?: return@runCatching
            if (throwable != null) framework.log(priority, tag, message, throwable)
            else framework.log(priority, tag, message)
        }
    }

    fun d(tag: String, message: String) {
        if (level < LOG_LEVEL_DEBUG) return
        emit(android.util.Log.INFO, tag, message)
    }

    fun i(tag: String, message: String) {
        if (level < LOG_LEVEL_BASIC) return
        emit(android.util.Log.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (level < LOG_LEVEL_BASIC) return
        emit(android.util.Log.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        emit(android.util.Log.ERROR, tag, message, throwable)
    }
}
