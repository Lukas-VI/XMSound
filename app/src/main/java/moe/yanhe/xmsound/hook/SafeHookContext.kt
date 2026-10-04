package moe.yanhe.xmsound.hook

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import moe.yanhe.xmsound.hook.bluetooth.HyperAdapterProbe
import moe.yanhe.xmsound.hook.bluetooth.MiuiHeadsetBinderProbe
import moe.yanhe.xmsound.hook.bluetooth.XiaomiHeadsetSpoofHook
import moe.yanhe.xmsound.hook.milink.MiLinkHeadsetProbe
import moe.yanhe.xmsound.hook.milink.MiLinkHeadsetSpoofHook
import moe.yanhe.xmsound.hook.settings.SettingsToastProbe

/**
 * Registration table for the hook layer.
 *
 * Each entry names a scoped package and the hooks to install in it. Kept in one place so the
 * targets for the running ROM are easy to audit and to update after a system update.
 */
object HookRegistry {

    fun hooksFor(packageName: String, probeEnabled: Boolean): List<HookContext> = buildList {
        when (packageName) {
            "com.android.bluetooth", "com.xiaomi.bluetooth", "com.milink.service" -> {
                if (probeEnabled) add(ClassProbe())
            }
        }
        when (packageName) {
            "com.android.bluetooth" -> add(HyperAdapterProbe())
            "com.xiaomi.bluetooth" -> {
                add(XiaomiHeadsetSpoofHook())
                add(MiuiHeadsetBinderProbe())
            }
            "com.milink.service" -> {
                add(MiLinkHeadsetSpoofHook())
                if (probeEnabled) add(MiLinkHeadsetProbe())
            }
            "com.android.settings" -> add(SettingsToastProbe())
        }
    }
}

/**
 * Base class for hooks that must not break the host process.
 *
 * HyperOS internals move between releases, so every individual hook is installed inside its own
 * `runCatching`: one renamed method must not stop the rest of the module from working.
 */
abstract class SafeHookContext : HookContext() {

    /** Guards against refusing to re-install a hook that is already in place. */
    private val installed = ConcurrentHashMap.newKeySet<String>()

    protected fun install(name: String, block: () -> Unit) {
        if (!installed.add(name)) return
        runCatching { block() }
            .onSuccess { HookLog.d(tag, "hooked $name") }
            .onFailure { HookLog.w(tag, "could not hook $name: ${it.javaClass.simpleName}: ${it.message}") }
    }

    protected open val tag: String get() = javaClass.simpleName

    /** Log only when the observed value changes, so UI polling does not flood logcat. */
    private val lastLogged = ConcurrentHashMap<String, Any?>()

    protected fun logOnChange(key: String, value: Any?) {
        if (lastLogged.put(key, value) != value) {
            HookLog.i(tag, "$key = $value")
        }
    }

    /** Resolve a no-arg getter and run [block] after it, keeping the original return value. */
    protected fun hookGetter(className: String, methodName: String, block: (Any?) -> Unit = {}) {
        install("$className#$methodName") {
            val method: Method = findMethod(className, methodName)
            hookAfter(method) { block(result) }
        }
    }

    /**
     * Like [findMethod], but walks up the class hierarchy. Needed for framework methods a class
     * merely inherits (`Service.onBind`, `Binder.onTransact`), which `getDeclaredMethod` misses.
     */
    protected fun findMethodAnywhere(className: String, methodName: String, vararg parameterTypes: Class<*>): Method {
        var cls: Class<*>? = findClass(className)
        while (cls != null) {
            runCatching {
                return cls.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException("$className#$methodName")
    }

    /**
     * The hooked process's Application, used to reach our own process through the state provider.
     * `ActivityThread.currentApplication()` is the only context available before any of the
     * hooked objects have been seen.
     */
    protected fun appContext(): android.content.Context? = HeadsetStateCache.appContext()

    /**
     * Live headset state, served from the per-process cache.
     *
     * Never call the provider directly from a hook: HyperOS reads these values on the main thread
     * while laying out the UI, and a synchronous binder round trip there freezes the process.
     */
    protected fun queryHeadsetState(): android.os.Bundle? {
        HeadsetStateCache.start()
        return HeadsetStateCache.get()
    }
}
