package moe.yanhe.xmsound.hook

import io.github.libxposed.api.XposedModule

/** Level-gated wrapper around [XposedModule.log] used by every hook. */
object HookLog {
    const val LOG_LEVEL_OFF = 0
    const val LOG_LEVEL_BASIC = 1
    const val LOG_LEVEL_DEBUG = 2

    @Volatile
    var module: XposedModule? = null

    @Volatile
    var level: Int = LOG_LEVEL_DEBUG

    fun d(tag: String, message: String) {
        if (level < LOG_LEVEL_DEBUG) return
        runCatching { module?.log(android.util.Log.INFO, tag, message) }
    }

    fun i(tag: String, message: String) {
        if (level < LOG_LEVEL_BASIC) return
        runCatching { module?.log(android.util.Log.INFO, tag, message) }
    }

    fun w(tag: String, message: String) {
        if (level < LOG_LEVEL_BASIC) return
        runCatching { module?.log(android.util.Log.WARN, tag, message) }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) module?.log(android.util.Log.ERROR, tag, message, throwable)
            else module?.log(android.util.Log.ERROR, tag, message)
        }
    }
}
