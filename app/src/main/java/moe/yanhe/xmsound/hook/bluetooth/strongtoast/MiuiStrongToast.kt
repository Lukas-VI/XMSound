package moe.yanhe.xmsound.hook.bluetooth.strongtoast

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import java.io.File
import kotlinx.serialization.json.Json
import moe.yanhe.xmsound.hook.HookLog

/**
 * MIUI "strong toast" - the native connection popup and the big Super Island slot.
 *
 * Why this exists instead of the official focus-notification API: HyperOS authorises focus
 * notifications **online** through `com.xiaomi.xms.auth.IAuthService`
 * (`miui.systemui.notification.auth.AuthManager`), and an authorisation failure makes SystemUI drop
 * the island (`FocusPlugin: onAuthFailed`, then `removeByKey`). A third-party module cannot pass
 * that check.
 *
 * The strong toast instead goes through `StatusBarManager.setStatus(..., "strong_toast_action", ...)`
 * addressed as `com.xiaomi.bluetooth`, which is already authorised. It carries an `island_param`
 * with `notifyId = headset_wear_notification`, which is what places it in the island area.
 *
 * This must be called from inside the `com.xiaomi.bluetooth` process: `setStatus` needs the
 * signature-level `STATUS_BAR` permission, and the earphone animations are read straight out of that
 * package's own `res/raw` - nothing is bundled or copied out of it.
 */
object MiuiStrongToast {

    private const val TAG = "StrongToast"

    /** The package that owns the assets and is already authorised for strong toasts. */
    private const val HOST_PACKAGE = "com.xiaomi.bluetooth"

    private const val ACTION = "strong_toast_action"
    private const val CATEGORY_VIDEO_TEXT_TEXT_VIDEO = "video_text_text_video"
    private const val NOTIFY_ID = "headset_wear_notification"

    private const val DURATION_MS = 5_000L
    private const val LOW_BATTERY = 20

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /**
     * Show the popup with each bud's level.
     *
     * [leftLevel] / [rightLevel] are null when that bud is absent, in which case the "not in ear"
     * animation is used and no percentage is drawn - the same behaviour as a first-party earbud.
     */
    fun showBattery(
        context: Context,
        leftLevel: Int?,
        leftCharging: Boolean,
        rightLevel: Int?,
        rightCharging: Boolean,
    ): Boolean {
        if (leftLevel == null && rightLevel == null) return false

        return runCatching {
            val leftClip = clipUri(context, if (leftLevel != null) "earphone_left_inear" else "earphone_left_no_inear")
            val rightClip = clipUri(context, if (rightLevel != null) "earphone_right_inear" else "earphone_right_no_inear")
            if (leftClip == null || rightClip == null) {
                HookLog.w(TAG, "earphone animations unavailable in $HOST_PACKAGE")
                return false
            }

            val toast = StringToastBean(
                left = side(leftClip, leftLevel, leftCharging, island = false),
                right = side(rightClip, rightLevel, rightCharging, island = false),
            )
            val island = StringToastBean(
                left = side(leftClip, leftLevel, leftCharging, island = true),
                right = side(rightClip, rightLevel, rightCharging, island = true),
            )

            val bundle = Bundle().apply {
                putString("package_name", HOST_PACKAGE)
                putString("strong_toast_category", CATEGORY_VIDEO_TEXT_TEXT_VIDEO)
                putString("status_bar_strong_toast", "show_custom_strong_toast")
                putString("param", json.encodeToString(StringToastBean.serializer(), toast))
                putString("island_param", json.encodeToString(StringToastBean.serializer(), island))
                putString("notifyId", NOTIFY_ID)
                putLong("duration", DURATION_MS)
                putParcelable("target", null)
            }

            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass
                .getMethod("setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java)
                .invoke(service, 1, ACTION, bundle)

            HookLog.i(TAG, "strong toast shown L=$leftLevel R=$rightLevel")
            true
        }.getOrElse {
            HookLog.w(TAG, "strong toast failed: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /** Tear the popup down early, e.g. when the headset disconnects. */
    fun hide(context: Context) {
        runCatching {
            val bundle = Bundle().apply {
                putString("package_name", HOST_PACKAGE)
                putString("status_bar_strong_toast", "hide_strong_toast")
            }
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass
                .getMethod("setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java)
                .invoke(service, 1, ACTION, bundle)
        }.onFailure { HookLog.w(TAG, "hide failed: ${it.message}") }
    }

    private fun side(clipUri: String, level: Int?, charging: Boolean, island: Boolean): ToastSide {
        val iconType = if (island) 0 else 1
        return ToastSide(
            iconParams = IconParams(
                category = "raw",
                iconFormat = "mp4",
                iconResName = clipUri,
                iconType = iconType,
            ),
            textParams = level?.let {
                TextParams(
                    text = "${it.coerceIn(0, 100)}%",
                    textColor = when {
                        charging -> Color.GREEN
                        it <= LOW_BATTERY -> Color.RED
                        else -> Color.WHITE
                    },
                    viewFlags = if (island) null else 0,
                    turnAnim = if (island) true else null,
                )
            },
        )
    }

    /**
     * Resolve one of the host package's earphone animations.
     *
     * The clip is copied into the host's own files directory (this process already is that package)
     * and handed to SystemUI as a content URI, so SystemUI never needs access to our module's
     * resources - and the animation stays Xiaomi's own, which is what makes the popup look native.
     */
    private fun clipUri(context: Context, name: String): String? {
        val file = File(context.filesDir, "$name.mp4")
        if (!file.exists() || file.length() == 0L) {
            val resourceId = listOf(name, name.removeSuffix("_inear"))
                .distinct()
                .firstNotNullOfOrNull { candidate ->
                    context.resources.getIdentifier(candidate, "raw", context.packageName).takeIf { it != 0 }
                } ?: return null

            val copied = runCatching {
                context.resources.openRawResource(resourceId).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setReadable(true, false)
            }.isSuccess
            if (!copied) return null
        }

        val uri = "content://$HOST_PACKAGE.fileprovider/internal_files/$name.mp4"
        runCatching {
            context.grantUriPermission(
                "com.android.systemui",
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        return uri
    }
}
