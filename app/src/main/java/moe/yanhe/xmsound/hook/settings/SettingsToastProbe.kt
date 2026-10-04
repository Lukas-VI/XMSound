package moe.yanhe.xmsound.hook.settings

import android.content.Intent
import android.widget.Toast
import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.hook.SafeHookContext
import moe.yanhe.xmsound.hook.getObjectField

/**
 * Makes the Bluetooth settings page's noise-control buttons work, and explains refusals.
 *
 * The page has its own precondition that the Fusion Device Center does not:
 *
 * ```
 * MiuiHeadsetFragment.updateAncMode(SourceFile:3801) -> ToastUtil.show("请连接并佩戴耳机")
 * MiuiHeadsetFragment$24.onClick(SourceFile:3759)
 * ```
 *
 * Everything the module spoofs is already in place there (`mDeviceId`, `mSupport`,
 * `mSupportAnc=true`), but the check still refuses, and the deciding condition is inside an
 * obfuscated line range. So instead of guessing it, the button is intercepted and handled the way
 * the Device Center already does it: forward the mode to the headset, then let the status push
 * refresh the page.
 *
 * The toast hook is kept because it is what pinpointed the method, and it stays useful when a new
 * ROM moves the check.
 */
class SettingsToastProbe : SafeHookContext() {

    override val tag: String get() = "SettingsHeadset"

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

        hookUpdateAncMode()

        HookLog.i(tag, "settings headset hook installed in $packageName")
    }

    /**
     * Replace `updateAncMode(int, boolean)` with our own handling.
     *
     * The original shows the refusal toast and gives up; skipping it means no toast is shown and the
     * mode change goes to the headset instead.
     */
    private fun hookUpdateAncMode() {
        install("MiuiHeadsetFragment#updateAncMode") {
            val fragment = findClassOrNull(FRAGMENT) ?: run {
                HookLog.w(tag, "MISSING $FRAGMENT")
                return@install
            }
            fragment.declaredMethods
                .filter { it.name == "updateAncMode" }
                .forEach { method ->
                    method.isAccessible = true
                    hookBefore(method) {
                        val mode = args.getOrNull(0) as? Int
                        val fromUser = args.getOrNull(1) as? Boolean ?: false
                        if (mode == null) return@hookBefore

                        // Only user taps are ours to satisfy; internal calls keep working as-is.
                        if (!fromUser) return@hookBefore

                        HookLog.i(tag, "updateAncMode($mode, fromUser=$fromUser) -> handling it ourselves")
                        forwardMode(mode)
                        // Swallow the original, which is the code path that shows the toast.
                        result = null
                    }
                }
        }
    }

    /** The buttons use the same numbering as MiLink: 0 = off, 1 = noise cancelling, 2 = ambient. */
    private fun forwardMode(mode: Int) {
        val name = when (mode) {
            MODE_OFF -> "off"
            MODE_NC -> "nc"
            MODE_AMBIENT -> "ambient"
            else -> null
        }
        if (name == null) {
            HookLog.w(tag, "unknown ANC mode $mode, not forwarding")
            return
        }
        val context = appContext() ?: return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_SET_NOISE).setPackage(MODULE_PACKAGE).putExtra("mode", name),
            )
            HookLog.i(tag, "forwarded mode=$name")
        }.onFailure { HookLog.w(tag, "forward failed: ${it.message}") }
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
        const val FRAGMENT = "com.android.settings.bluetooth.MiuiHeadsetFragment"

        const val MODULE_PACKAGE = "moe.yanhe.xmsound"
        const val ACTION_SET_NOISE = "moe.yanhe.xmsound.action.SET_NOISE"

        const val MODE_OFF = 0
        const val MODE_NC = 1
        const val MODE_AMBIENT = 2

        val INTERESTING = listOf("佩戴", "连接", "耳机", "wear", "connect")
    }
}
