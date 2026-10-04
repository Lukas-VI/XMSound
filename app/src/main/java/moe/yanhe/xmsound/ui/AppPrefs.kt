package moe.yanhe.xmsound.ui

import android.content.Context

/** Small typed wrapper over the app's SharedPreferences. */
object AppPrefs {
    private const val NAME = "xmsound_settings"
    private const val KEY_AUTO_CONNECT = "auto_connect"

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
    }
}
