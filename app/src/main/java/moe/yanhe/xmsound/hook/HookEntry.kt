package moe.yanhe.xmsound.hook

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * libxposed entry point (see `assets/xposed_init`).
 *
 * The module hooks the processes that make up HyperOS' Bluetooth / interconnect stack so that a
 * Sony WF-1000XM5 can be presented and controlled like a first-party headset.
 */
class HookEntry : XposedModule() {

    private companion object {
        const val TAG = "XMSound-HookEntry"
        const val PREFS_NAME = "xmsound_settings"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        HookLog.module = this

        val hook: HookContext? = when (param.packageName) {
            "com.android.bluetooth" -> null // TODO AOSP BT stack integration
            "com.xiaomi.bluetooth" -> null // TODO MIUI/HyperOS Bluetooth UI integration
            "com.milink.service" -> null // TODO Fusion Device Center integration
            else -> null
        }

        if (hook == null) {
            HookLog.d(TAG, "package loaded, no hook registered yet: ${param.packageName}")
            return
        }

        hook.module = this
        hook.appClassLoader = param.defaultClassLoader
        hook.packageName = param.packageName
        hook.prefs = getRemotePreferences(PREFS_NAME)

        runCatching { hook.onHook() }
            .onFailure { HookLog.e(TAG, "onHook failed for ${param.packageName}", it) }
    }
}
