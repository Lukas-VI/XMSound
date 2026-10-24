package moe.yanhe.xmsound.hook

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import moe.yanhe.xmsound.hook.runtime.SonyRuntime
import moe.yanhe.xmsound.pods.sony.SonyBridge

/**
 * libxposed entry point (see `assets/xposed_init`).
 *
 * The module hooks the processes that make up HyperOS' Bluetooth / interconnect stack so that a
 * Sony WF-1000XM5 can be presented and controlled like a first-party headset.
 */
class HookEntry : XposedModule() {

    companion object {
        const val TAG = "XMSound-HookEntry"
        private const val PREFS_NAME = "xmsound_settings"

        /**
         * Enables the read-only probes ([ClassProbe], [HyperAdapterProbe], [MiuiHeadsetBinderProbe]),
         * which dump the live Bluetooth-stack API surface into logcat. Useful after every ROM
         * update, because HyperOS re-layers the AOSP stack between releases - but they hook hot
         * paths (e.g. every Binder transaction) and are pure overhead in daily use, so they stay
         * off now that the hook targets for the running ROM are confirmed. Flip to true to survey a
         * new ROM.
         */
        const val PROBE_ENABLED = false
    }

    /**
     * Called in every process the module is injected into, before any package is loaded. This is
     * the only place that proves the module is actually active, so it always logs.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookLog.module = this
        HookLog.i(TAG, "module loaded in process=${param.processName} systemServer=${param.isSystemServer}")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        HookLog.module = this
        HookLog.i(TAG, "package loaded: ${param.packageName}")

        // The session owner is independent of whatever probes this package installs, so it is
        // started before the early return below. The host classloader lets it reach the profile
        // services for audio handoff (流转).
        if (param.packageName == SonyBridge.OWNER_PACKAGE) {
            HookLog.i(TAG, "starting the headset session owner in ${param.packageName}")
            SonyRuntime.bootstrap(param.defaultClassLoader)
        }

        val hooks = HookRegistry.hooksFor(param.packageName, PROBE_ENABLED)

        if (hooks.isEmpty()) return

        hooks.forEach { hook ->
            hook.module = this
            hook.appClassLoader = param.defaultClassLoader
            hook.packageName = param.packageName
            hook.prefs = getRemotePreferences(PREFS_NAME)

            runCatching { hook.onHook() }
                .onFailure { HookLog.e(TAG, "onHook failed for ${hook.javaClass.simpleName}", it) }
        }
    }
}
