package moe.yanhe.xmsound.pods.sony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Intent-driven control surface for the headset session.
 *
 * This exists for two reasons:
 *  - the HyperOS hook layer runs in other processes and needs a way to drive the session, and
 *  - it makes the module testable without the UI, e.g.
 *    `adb shell am broadcast -a moe.yanhe.xmsound.action.SET_NOISE --es mode ambient`
 */
class SonyControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "XMSound-Sony"

        const val ACTION_CONNECT = "moe.yanhe.xmsound.action.CONNECT"
        const val ACTION_DISCONNECT = "moe.yanhe.xmsound.action.DISCONNECT"
        const val ACTION_RECONNECT = "moe.yanhe.xmsound.action.RECONNECT"
        const val ACTION_REFRESH = "moe.yanhe.xmsound.action.REFRESH"
        const val ACTION_SET_NOISE = "moe.yanhe.xmsound.action.SET_NOISE"
        const val ACTION_SET_AMBIENT_LEVEL = "moe.yanhe.xmsound.action.SET_AMBIENT_LEVEL"
        const val ACTION_SET_FOCUS_ON_VOICE = "moe.yanhe.xmsound.action.SET_FOCUS_ON_VOICE"
        const val ACTION_CYCLE_NOISE = "moe.yanhe.xmsound.action.CYCLE_NOISE"

        const val EXTRA_MODE = "mode"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_ENABLED = "enabled"

        const val MODE_OFF = "off"
        const val MODE_NC = "nc"
        const val MODE_AMBIENT = "ambient"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val controller = SonyHeadsetController.get(context)
        val action = intent.action ?: return
        Log.i(TAG, "control action=$action extras=${intent.extras}")

        when (action) {
            ACTION_CONNECT -> controller.connect()
            ACTION_DISCONNECT -> controller.disconnect()
            ACTION_RECONNECT -> controller.reconnect()
            ACTION_REFRESH -> controller.refresh()
            ACTION_CYCLE_NOISE -> controller.cycleNoiseMode()

            ACTION_SET_NOISE -> {
                val mode = when (intent.getStringExtra(EXTRA_MODE)?.lowercase()) {
                    MODE_OFF -> SonyNoiseMode.OFF
                    MODE_NC -> SonyNoiseMode.NOISE_CANCELLING
                    MODE_AMBIENT -> SonyNoiseMode.AMBIENT_SOUND
                    else -> return
                }
                controller.setNoiseMode(mode)
            }

            ACTION_SET_AMBIENT_LEVEL -> {
                if (!intent.hasExtra(EXTRA_LEVEL)) return
                controller.setAmbientLevel(intent.getIntExtra(EXTRA_LEVEL, 0))
            }

            ACTION_SET_FOCUS_ON_VOICE ->
                controller.setFocusOnVoice(intent.getBooleanExtra(EXTRA_ENABLED, false))
        }
    }
}
