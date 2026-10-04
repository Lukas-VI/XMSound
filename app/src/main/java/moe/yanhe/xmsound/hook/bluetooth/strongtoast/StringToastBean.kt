package moe.yanhe.xmsound.hook.bluetooth.strongtoast

import kotlinx.serialization.Serializable

/**
 * JSON payload for MIUI's strong toast.
 *
 * The shape matters: `viewFlags` and `turnAnim` are only meaningful in one of the two forms, so the
 * toast and island payloads are built from the same beans with a couple of fields overridden.
 * Encoded with `encodeDefaults = true` and `explicitNulls = false`, matching what SystemUI expects.
 */
@Serializable
data class StringToastBean(
    var left: ToastSide? = null,
    var right: ToastSide? = null,
)

@Serializable
data class ToastSide(
    var iconParams: IconParams? = null,
    var textParams: TextParams? = null,
)

/**
 * [category] is one of `raw`, `drawable`, `file`, `mipmap`; [iconFormat] one of `mp4`, `png`, `svg`.
 *
 * `iconType` 1 means "animate once", 0 means "hold"; the island form uses 0 so the artwork stays put.
 */
@Serializable
data class IconParams(
    var category: String? = null,
    var iconFormat: String? = null,
    var iconResName: String? = null,
    var iconType: Int = 0,
)

@Serializable
data class TextParams(
    var text: String? = null,
    var textColor: Int = 0,
    var viewFlags: Int? = null,
    var turnAnim: Boolean? = null,
)
