package moe.yanhe.xmsound.pods.sony

/**
 * The contract between the process that owns the session and everyone else.
 *
 * The session lives in `com.android.bluetooth` because that process is always alive: it used to live
 * in the module's own process, which meant clearing the module from recents killed the headset
 * connection and every system surface went stale.
 *
 * Both directions are broadcasts:
 *
 *  - owner -> everyone: [ACTION_STATE] with the encoded state, [ACTION_TRAFFIC] per protocol line
 *  - anyone -> owner: the command actions below
 *
 * State broadcasts are addressed with `setPackage` to each interested package, so they are not
 * visible to unrelated apps.
 */
object SonyBridge {

    /** Package that owns the session. Chosen because the Bluetooth stack is never killed. */
    const val OWNER_PACKAGE = "com.android.bluetooth"

    const val MODULE_PACKAGE = "moe.yanhe.xmsound"

    /** Everything that wants to know the headset state, owner excluded. */
    val STATE_TARGETS = listOf(MODULE_PACKAGE, "com.xiaomi.bluetooth", "com.milink.service")

    // ------------------------------------------------------------ owner -> everyone

    const val ACTION_STATE = "moe.yanhe.xmsound.action.STATE"
    const val ACTION_TRAFFIC = "moe.yanhe.xmsound.action.TRAFFIC"

    /** The encoded [SonyStateBundle]. */
    const val EXTRA_STATE = "state"

    /** One protocol log line, for the debug page. */
    const val EXTRA_LINE = "line"
    const val EXTRA_DIRECTION = "direction"

    /** A whole traffic dump, as `"> 3e 0c ..."` strings, oldest first. */
    const val EXTRA_LINES = "lines"

    // ------------------------------------------------------------ everyone -> owner

    private const val PREFIX = "moe.yanhe.xmsound.action."

    const val ACTION_CONNECT = "${PREFIX}CONNECT"
    const val ACTION_DISCONNECT = "${PREFIX}DISCONNECT"
    const val ACTION_RECONNECT = "${PREFIX}RECONNECT"
    const val ACTION_REFRESH = "${PREFIX}REFRESH"
    const val ACTION_CYCLE_NOISE = "${PREFIX}CYCLE_NOISE"
    const val ACTION_SET_NOISE = "${PREFIX}SET_NOISE"
    const val ACTION_SET_AMBIENT_LEVEL = "${PREFIX}SET_AMBIENT_LEVEL"
    const val ACTION_SET_FOCUS_ON_VOICE = "${PREFIX}SET_FOCUS_ON_VOICE"
    const val ACTION_SHOW_ISLAND = "${PREFIX}SHOW_ISLAND"
    const val ACTION_SHOW_CARD = "${PREFIX}SHOW_CARD"
    const val ACTION_APPLY_SETTINGS = "${PREFIX}APPLY_SETTINGS"
    const val ACTION_REQUEST_STATE = "${PREFIX}REQUEST_STATE"
    const val ACTION_REQUEST_TRAFFIC = "${PREFIX}REQUEST_TRAFFIC"

    /**
     * Audio handoff (耳机流转): bring the headset's audio to this phone, or release it so the
     * headset's multipoint second host takes over. The device center's 流转 entry sends these.
     */
    const val ACTION_AUDIO_CONNECT = "${PREFIX}AUDIO_CONNECT"
    const val ACTION_AUDIO_DISCONNECT = "${PREFIX}AUDIO_DISCONNECT"

    const val EXTRA_MODE = "mode"
    const val EXTRA_LEVEL = "level"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_ISLAND_ENABLED = "islandEnabled"
    const val EXTRA_CARD_ENABLED = "cardEnabled"

    /**
     * Accept both the enum names and the short names the notification card and the hooks use.
     *
     * Callers are spread across three processes written at different times, so being liberal here
     * removes a whole class of "the button silently did nothing" bugs.
     */
    fun parseMode(value: String?): SonyNoiseMode? = when (value?.lowercase()) {
        "off", "close", "closed", SonyNoiseMode.OFF.name.lowercase() -> SonyNoiseMode.OFF
        "nc", "anc", "noise_cancelling", "noisecancelling", "noise_canceling",
        SonyNoiseMode.NOISE_CANCELLING.name.lowercase(),
        -> SonyNoiseMode.NOISE_CANCELLING
        "ambient", "transparency", "transparent", "ambient_sound",
        SonyNoiseMode.AMBIENT_SOUND.name.lowercase(),
        -> SonyNoiseMode.AMBIENT_SOUND
        else -> null
    }

    /** Every command the owner listens for; used to build the receiver's intent filter. */
    val COMMAND_ACTIONS = listOf(
        ACTION_CONNECT,
        ACTION_DISCONNECT,
        ACTION_RECONNECT,
        ACTION_REFRESH,
        ACTION_CYCLE_NOISE,
        ACTION_SET_NOISE,
        ACTION_SET_AMBIENT_LEVEL,
        ACTION_SET_FOCUS_ON_VOICE,
        ACTION_SHOW_ISLAND,
        ACTION_SHOW_CARD,
        ACTION_APPLY_SETTINGS,
        ACTION_AUDIO_CONNECT,
        ACTION_AUDIO_DISCONNECT,
        ACTION_REQUEST_STATE,
        ACTION_REQUEST_TRAFFIC,
    )
}
