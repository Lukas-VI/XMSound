package moe.yanhe.xmsound.pods.sony

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Icon
import moe.yanhe.xmsound.R

/**
 * The ongoing notification card for the headset.
 *
 * Mirrors what a first-party Xiaomi earbud gets: a persistent, silent card showing per-part battery
 * and the current noise mode, with the three modes as actions so the headset can be controlled
 * without opening anything.
 *
 * The card is posted by the **session owner** (`com.android.bluetooth`, see [SonyRuntime]), not by
 * the module's own process. That is deliberate: HyperOS' "clean background" force-stops user apps,
 * which cancels every notification they posted, so a module-process card disappeared the moment the
 * user swiped the app away and never came back. The Bluetooth process is never swept, so a card
 * hosted there survives - and its buttons talk to the owner's own command receiver directly.
 *
 * Because this class runs inside a foreign process, it must not resolve module resources through the
 * host `Context` (that would read the *host's* resource table with module IDs). Module strings and
 * action icons are loaded through the module package's own [Resources]; the small icon uses the
 * host application's icon, which the notification pipeline always accepts.
 */
object HeadsetNotificationCard {

    private const val CHANNEL_ID = "xmsound_headset"
    private const val NOTIFICATION_ID = 10011

    private const val REQUEST_NC = 1
    private const val REQUEST_AMBIENT = 2
    private const val REQUEST_OFF = 3
    private const val REQUEST_OPEN = 4

    /** Posted by a foreign process, so the module's resources need a package-scoped lookup. */
    @Volatile
    private var moduleResources: Resources? = null

    @Volatile
    private var channelCreated = false

    /** Suppresses identical re-posts, so a 30s poll cycle does not wake the notification pipeline. */
    @Volatile
    private var lastSignature: String? = null

    fun update(context: Context, state: SonyHeadsetState, deviceName: String?) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val res = resources(context)

        val title = deviceName ?: res?.getString(R.string.app_name) ?: "XMSound"
        val text = summary(context, res, state)
        val actions = state.connected
        val signature = "$title|$text|$actions"
        if (signature == lastSignature) return
        lastSignature = signature

        createChannel(manager, res)
        if (!manager.areNotificationsEnabled()) {
            // The Bluetooth uid posts OPP notifications, so this should be granted - but log it so a
            // ROM that disagrees is diagnosable instead of silently card-less.
            android.util.Log.w("XMSound-Card", "notifications are disabled for ${context.packageName}")
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            // The host's own application icon: a small icon from another package is not guaranteed
            // to survive the notification pipeline, an own-package one always is.
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (state.connected) {
            builder.addAction(action(context, REQUEST_NC, R.drawable.ic_anc_nc, noiseLabel(res, SonyNoiseMode.NOISE_CANCELLING), "nc"))
            builder.addAction(
                action(context, REQUEST_AMBIENT, R.drawable.ic_anc_ambient, noiseLabel(res, SonyNoiseMode.AMBIENT_SOUND), "ambient"),
            )
            builder.addAction(action(context, REQUEST_OFF, R.drawable.ic_anc_off, noiseLabel(res, SonyNoiseMode.OFF), "off"))
        }

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { android.util.Log.w("XMSound-Card", "post failed: ${it.message}") }
    }

    fun cancel(context: Context) {
        lastSignature = null
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    /** Re-post even if the content is unchanged, e.g. for the explicit SHOW_CARD command. */
    fun force(context: Context, state: SonyHeadsetState, deviceName: String?) {
        lastSignature = null
        update(context, state, deviceName)
    }

    // ---------------------------------------------------------------- host-safe resources

    private fun resources(context: Context): Resources? =
        moduleResources ?: runCatching {
            context.packageManager.getResourcesForApplication(SonyBridge.MODULE_PACKAGE)
        }.getOrNull()?.also { moduleResources = it }

    private fun createChannel(manager: NotificationManager, res: Resources?) {
        if (channelCreated) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                res?.getString(R.string.notif_channel_headset) ?: "Headset status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        channelCreated = true
    }

    /** `左 88% · 右 88% · 盒 30% · 降噪`, with unknown parts shown as a dash. */
    private fun summary(context: Context, res: Resources?, state: SonyHeadsetState): String {
        val unknown = res?.getString(R.string.battery_unknown) ?: "—"

        fun cell(bud: SonyBudBattery?): String = bud?.let { "${it.level}%" } ?: unknown
        fun label(resId: Int, fallback: String): String = res?.getString(resId) ?: fallback

        val mode = when (state.noise.mode) {
            SonyNoiseMode.NOISE_CANCELLING -> noiseLabel(res, SonyNoiseMode.NOISE_CANCELLING)
            SonyNoiseMode.AMBIENT_SOUND -> noiseLabel(res, SonyNoiseMode.AMBIENT_SOUND)
            SonyNoiseMode.OFF -> noiseLabel(res, SonyNoiseMode.OFF)
            SonyNoiseMode.UNKNOWN -> null
        }

        return buildString {
            append(label(R.string.batt_left_pod, "L")).append(' ').append(cell(state.battery.left))
            append(" · ").append(label(R.string.batt_right_pod, "R")).append(' ')
            append(cell(state.battery.right))
            append(" · ").append(label(R.string.pod_case, "Case")).append(' ')
            append(cell(state.battery.case))
            mode?.let { append(" · ").append(it) }
        }
    }

    private fun noiseLabel(res: Resources?, mode: SonyNoiseMode): String = when (mode) {
        SonyNoiseMode.NOISE_CANCELLING -> res?.getString(R.string.noise_control_nc) ?: "NC"
        SonyNoiseMode.AMBIENT_SOUND -> res?.getString(R.string.noise_control_ambient) ?: "Ambient"
        else -> res?.getString(R.string.noise_control_off) ?: "Off"
    }

    private fun action(
        context: Context,
        requestCode: Int,
        iconRes: Int,
        label: String,
        mode: String,
    ): Notification.Action {
        // Straight to the owner's own receiver; the module process is not involved at all.
        val intent = Intent(SonyBridge.ACTION_SET_NOISE)
            .setPackage(SonyBridge.OWNER_PACKAGE)
            .putExtra(SonyBridge.EXTRA_MODE, mode)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(SonyBridge.MODULE_PACKAGE, iconRes),
            label,
            pending,
        ).build()
    }

    private fun openApp(context: Context): PendingIntent {
        // A component name, not a class reference: this class runs in a process whose classloader
        // has the module's (injected) classes, but the target activity must resolve as an explicit
        // component of the module package either way.
        val intent = Intent()
            .setClassName(SonyBridge.MODULE_PACKAGE, "moe.yanhe.xmsound.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
