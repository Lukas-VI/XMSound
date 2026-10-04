package moe.yanhe.xmsound.hook.settings

import android.widget.Toast
import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.hook.SafeHookContext
import moe.yanhe.xmsound.hook.getObjectField

/**
 * Finds out why HyperOS' headset page refuses an action.
 *
 * The noise-control buttons on the Bluetooth settings page answer with
 * "请连接并佩戴耳机" (connect and wear the earbuds) even though the Fusion Device Center accepts
 * the same commands. The message is a resource string, so its call site cannot be found by name.
 *
 * Hooking `Toast.show` and printing the stack trace pinpoints the exact method that makes the
 * decision, which is far quicker than guessing. Read-only apart from the log.
 */
class SettingsToastProbe : SafeHookContext() {

    override val tag: String get() = "SettingsToast"

    override fun onHook() {
        install("Toast#show") {
            val method = findMethodAnywhere("android.widget.Toast", "show")
            hookBefore(method) {
                val text = toastText(instance) ?: return@hookBefore
                if (!INTERESTING.any { text.contains(it) }) return@hookBefore
                HookLog.i(tag, "toast text=$text")
                Thread.currentThread().stackTrace
                    .drop(1)
                    .take(18)
                    .forEach { HookLog.i(tag, "    at $it") }
            }
        }

        HookLog.i(tag, "settings toast probe installed in $packageName")
    }

    /** AOSP keeps the text in `mText`; MIUI has used `mMessage` on some builds. */
    private fun toastText(toast: Any?): String? {
        if (toast == null) return null
        listOf("mText", "mMessage").forEach { field ->
            val value = runCatching { getObjectField(toast, field) }.getOrNull()
            if (value is CharSequence) return value.toString()
        }
        return null
    }

    private companion object {
        val INTERESTING = listOf("佩戴", "连接", "耳机", "wear", "connect")
    }
}
