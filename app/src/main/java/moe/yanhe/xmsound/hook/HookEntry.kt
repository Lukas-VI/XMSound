package moe.yanhe.xmsound.hook

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

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
         * Enables the read-only [ClassProbe], which dumps the live Bluetooth-stack API surface into
         * logcat. Useful after every ROM update, because HyperOS re-layers the AOSP stack between
         * releases. Disable it once the hook targets for the running ROM are confirmed.
         */
        const val PROBE_ENABLED = true
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

        val hooks = mutableListOf<HookContext>()

        when (param.packageName) {
            "com.android.bluetooth", "com.xiaomi.bluetooth" -> {
                if (PROBE_ENABLED) hooks += ClassProbe()
                // TODO: headset state dispatcher + HyperHeadsetService spoofing
            }
            "com.milink.service" -> {
                // TODO: Fusion Device Center (融合设备中心) integration
            }
        }

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
