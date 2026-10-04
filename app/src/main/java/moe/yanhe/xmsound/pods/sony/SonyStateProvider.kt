package moe.yanhe.xmsound.pods.sony

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Exposes the live headset state to the module's hook layer.
 *
 * The hooks run inside `com.xiaomi.bluetooth` / `com.milink.service` while the SPP session lives in
 * the module's own process, so the state has to cross a process boundary. `ContentProvider.call` is
 * synchronous and cheap, which matters because HyperOS' headset UI polls.
 *
 * Two entries are served:
 *  - `state`   - the ready-to-send MIUI status string plus the device address
 *  - `connect` - asks the app to (re)establish the session
 */
class SonyStateProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "moe.yanhe.xmsound.state"
        const val METHOD_STATE = "state"
        const val METHOD_CONNECT = "connect"

        const val KEY_PAYLOAD = "payload"
        const val KEY_ADDRESS = "address"
        const val KEY_CONNECTED = "connected"
        const val KEY_MODEL = "model"

        fun uri(): Uri = Uri.parse("content://$AUTHORITY")
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val controller = SonyHeadsetController.get(context)

        return when (method) {
            METHOD_STATE -> {
                val state = controller.state
                Bundle().apply {
                    putString(KEY_PAYLOAD, MiuiHeadsetPayload.build(state))
                    putString(KEY_ADDRESS, controller.selectedDevice?.address)
                    putBoolean(KEY_CONNECTED, state.connected)
                    putString(KEY_MODEL, "WF-1000XM5")
                }
            }
            METHOD_CONNECT -> {
                controller.connect()
                Bundle().apply { putBoolean(KEY_CONNECTED, controller.isConnected) }
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
