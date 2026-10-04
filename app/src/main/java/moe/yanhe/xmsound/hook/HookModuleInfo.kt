package moe.yanhe.xmsound.hook

/** Static facts about the module, shared by the hook layer and the settings UI. */
object HookModuleInfo {
    const val MODULE_PACKAGE = "moe.yanhe.xmsound"

    /**
     * Processes the module hooks. Kept in sync with `res/values/arrays.xml` (`xposedscope`),
     * which is what LSPosed reads when it suggests a scope.
     */
    val scopePackages = listOf(
        "com.android.bluetooth",
        "com.xiaomi.bluetooth",
        "com.milink.service",
        "com.android.settings",
    )
}
