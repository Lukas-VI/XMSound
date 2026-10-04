package moe.yanhe.xmsound.ui

import android.content.Context

/** Small typed wrapper over the app's SharedPreferences. */
object AppPrefs {
    private const val NAME = "xmsound_settings"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_ISLAND = "battery_island"
    private const val KEY_NOTIFICATION_CARD = "notification_card"

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
    }

    /** Super Island popup on battery changes. */
    fun islandEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ISLAND, true)

    fun setIslandEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ISLAND, value).apply()
    }

    /** The ongoing notification card with noise-control actions. */
    fun notificationCardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION_CARD, true)

    fun setNotificationCardEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_CARD, value).apply()
    }
}
