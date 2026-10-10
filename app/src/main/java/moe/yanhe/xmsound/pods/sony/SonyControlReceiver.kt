package moe.yanhe.xmsound.pods.sony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Entry point for driving the headset from the module process.
 *
 * The session itself lives in `com.android.bluetooth` ([SonyBridge.OWNER_PACKAGE]), so this only
 * forwards the command there - the listener does not need the module to be running, which is the
 * whole point of the split.
 *
 * It also keeps the module testable without any UI, e.g.
 * `adb shell am broadcast -n moe.yanhe.xmsound/.pods.sony.SonyControlReceiver -a moe.yanhe.xmsound.action.SET_NOISE --es mode ambient`
 */
class SonyControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "XMSound-Sony"

        const val ACTION_CONNECT = SonyBridge.ACTION_CONNECT
        const val ACTION_DISCONNECT = SonyBridge.ACTION_DISCONNECT
        const val ACTION_RECONNECT = SonyBridge.ACTION_RECONNECT
        const val ACTION_REFRESH = SonyBridge.ACTION_REFRESH
        const val ACTION_SET_NOISE = SonyBridge.ACTION_SET_NOISE
        const val ACTION_SET_AMBIENT_LEVEL = SonyBridge.ACTION_SET_AMBIENT_LEVEL
        const val ACTION_SET_FOCUS_ON_VOICE = SonyBridge.ACTION_SET_FOCUS_ON_VOICE
        const val ACTION_CYCLE_NOISE = SonyBridge.ACTION_CYCLE_NOISE

        /** Ask the owner to raise the native popup; the owner holds the STATUS_BAR permission. */
        const val ACTION_SHOW_ISLAND = SonyBridge.ACTION_SHOW_ISLAND

        /** Re-post the module's own notification card. Handled locally, not forwarded. */
        const val ACTION_SHOW_CARD = "moe.yanhe.xmsound.action.SHOW_CARD"

        const val EXTRA_MODE = "mode"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_ENABLED = "enabled"

        /** The mode names the notification card's actions use. */
        const val MODE_OFF = "off"
        const val MODE_NC = "nc"
        const val MODE_AMBIENT = "ambient"

        /** Map the card's short names onto the protocol's enum names. */
        fun protocolMode(short: String?): String? = when (short?.lowercase()) {
            MODE_OFF -> SonyNoiseMode.OFF.name
            MODE_NC -> SonyNoiseMode.NOISE_CANCELLING.name
            MODE_AMBIENT -> SonyNoiseMode.AMBIENT_SOUND.name
            else -> null
        }

        /** Every action this receiver answers; kept in sync with the manifest. */
        val ACTIONS = listOf(
            ACTION_CONNECT,
            ACTION_DISCONNECT,
            ACTION_RECONNECT,
            ACTION_REFRESH,
            ACTION_SET_NOISE,
            ACTION_SET_AMBIENT_LEVEL,
            ACTION_SET_FOCUS_ON_VOICE,
            ACTION_CYCLE_NOISE,
            ACTION_SHOW_ISLAND,
            ACTION_SHOW_CARD,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "control action=$action")

        if (action == ACTION_SHOW_CARD) {
            val controller = SonyRemoteController.get(context)
            HeadsetNotificationCard.update(context, controller.state, controller.selectedDevice?.name)
            Log.i(TAG, "card re-posted state=${controller.state}")
            return
        }

        // Everything else belongs to the owner; forward it verbatim, remapping the card's short
        // mode names onto the enum names the owner understands.
        val forwarded = Intent(action).setPackage(SonyBridge.OWNER_PACKAGE)
        intent.extras?.let { forwarded.putExtras(it) }
        if (action == ACTION_SET_NOISE) {
            val mode = protocolMode(intent.getStringExtra(EXTRA_MODE))
            if (mode == null) {
                Log.w(TAG, "unknown mode '${intent.getStringExtra(EXTRA_MODE)}'")
                return
            }
            forwarded.putExtra(SonyBridge.EXTRA_MODE, mode)
        }
        runCatching { context.sendBroadcast(forwarded) }
            .onFailure { Log.w(TAG, "forward failed: ${it.message}") }
    }
}
