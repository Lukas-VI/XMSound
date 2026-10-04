package moe.yanhe.xmsound.pods.sony

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import moe.yanhe.xmsound.MainActivity
import moe.yanhe.xmsound.R

/**
 * The ongoing notification card for the headset.
 *
 * Mirrors what a first-party Xiaomi earbud gets: a persistent, silent card showing per-part battery
 * and the current noise mode, with the three modes as actions so the headset can be controlled
 * without opening anything.
 *
 * The actions go through [SonyControlReceiver], which is the same entry point the hook layer uses,
 * so there is exactly one code path for changing the mode.
 */
object HeadsetNotificationCard {

    private const val CHANNEL_ID = "xmsound_headset"
    private const val NOTIFICATION_ID = 10011

    private const val REQUEST_NC = 1
    private const val REQUEST_AMBIENT = 2
    private const val REQUEST_OFF = 3
    private const val REQUEST_OPEN = 4

    fun update(context: Context, state: SonyHeadsetState, deviceName: String?) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_headset),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_anc_nc)
            .setContentTitle(deviceName ?: context.getString(R.string.app_name))
            .setContentText(summary(context, state))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (state.connected) {
            builder.addAction(action(context, REQUEST_NC, R.drawable.ic_anc_nc, R.string.noise_control_nc, "nc"))
            builder.addAction(
                action(context, REQUEST_AMBIENT, R.drawable.ic_anc_ambient, R.string.noise_control_ambient, "ambient"),
            )
            builder.addAction(action(context, REQUEST_OFF, R.drawable.ic_anc_off, R.string.noise_control_off, "off"))
        }

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    /** `左 88% · 右 88% · 盒 30% · 降噪`, with unknown parts shown as a dash. */
    private fun summary(context: Context, state: SonyHeadsetState): String {
        fun cell(bud: SonyBudBattery?): String =
            bud?.let { "${it.level}%" } ?: context.getString(R.string.battery_unknown)

        val mode = when (state.noise.mode) {
            SonyNoiseMode.NOISE_CANCELLING -> context.getString(R.string.noise_control_nc)
            SonyNoiseMode.AMBIENT_SOUND -> context.getString(R.string.noise_control_ambient)
            SonyNoiseMode.OFF -> context.getString(R.string.noise_control_off)
            SonyNoiseMode.UNKNOWN -> null
        }

        return buildString {
            append(context.getString(R.string.batt_left_pod)).append(' ').append(cell(state.battery.left))
            append(" · ").append(context.getString(R.string.batt_right_pod)).append(' ')
            append(cell(state.battery.right))
            append(" · ").append(context.getString(R.string.pod_case)).append(' ')
            append(cell(state.battery.case))
            mode?.let { append(" · ").append(it) }
        }
    }

    private fun action(
        context: Context,
        requestCode: Int,
        iconRes: Int,
        labelRes: Int,
        mode: String,
    ): Notification.Action {
        val intent = Intent(context, SonyControlReceiver::class.java)
            .setAction(SonyControlReceiver.ACTION_SET_NOISE)
            .putExtra(SonyControlReceiver.EXTRA_MODE, mode)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, iconRes), context.getString(labelRes), pending)
            .build()
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
