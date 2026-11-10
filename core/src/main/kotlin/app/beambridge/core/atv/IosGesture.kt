package app.beambridge.core.atv

/**
 * Gesture ids for the iPhone's Apple TV Remote. All start with `ios_` so they never mix with BeamBridge button names.
 * Touch-surface gestures (`ios_swipe_*`, `ios_tap`) are kept apart from the remote's button presses (`ios_up`, `ios_select`…),
 * so each can trigger a different IR button.
 */
object IosGesture {
    const val PREFIX = "ios_"

    const val SWIPE_UP = "ios_swipe_up"
    const val SWIPE_DOWN = "ios_swipe_down"
    const val SWIPE_LEFT = "ios_swipe_left"
    const val SWIPE_RIGHT = "ios_swipe_right"
    const val TAP = "ios_tap"

    /** Labels for the setup screen, in display order. Unknown buttons still work as `ios_button_<n>`. */
    val LABELS: Map<String, String> = linkedMapOf(
        SWIPE_UP to "Swipe up", SWIPE_DOWN to "Swipe down", SWIPE_LEFT to "Swipe left", SWIPE_RIGHT to "Swipe right", TAP to "Tap",
        "ios_up" to "Up button", "ios_down" to "Down button", "ios_left" to "Left button", "ios_right" to "Right button",
        "ios_select" to "Select (click)", "ios_back" to "Back  ‹", "ios_tv" to "TV / Home", "ios_play_pause" to "Play / Pause",
        "ios_volume_up" to "Volume +", "ios_volume_down" to "Volume −", "ios_mute" to "Mute", "ios_power" to "Power",
        "ios_siri" to "Siri", "ios_channel_up" to "Channel +", "ios_channel_down" to "Channel −", "ios_guide" to "Guide",
        "ios_screensaver" to "Screensaver", "ios_sleep" to "Sleep", "ios_wake" to "Wake",
    )

    fun label(id: String): String = LABELS[id] ?: id.removePrefix(PREFIX).replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Companion `_hidC` button codes → gesture id. Codes 18/19 are Mute/Power on recent iOS. */
    fun fromHid(code: Long): String = when (code.toInt()) {
        1 -> "ios_up"; 2 -> "ios_down"; 3 -> "ios_left"; 4 -> "ios_right"
        5 -> "ios_back"; 6 -> "ios_select"; 7 -> "ios_tv"
        8 -> "ios_volume_up"; 9 -> "ios_volume_down"; 10 -> "ios_siri"
        11 -> "ios_screensaver"; 12 -> "ios_sleep"; 13 -> "ios_wake"; 14 -> "ios_play_pause"
        15 -> "ios_channel_up"; 16 -> "ios_channel_down"; 17 -> "ios_guide"
        18 -> "ios_mute"; 19 -> "ios_power"
        else -> "ios_button_$code"
    }

    /** Touch surface is 1000×1000. A lift within [TAP_MAX] of the start is a tap; a move of [SWIPE_MIN]+ is a swipe. */
    const val TAP_MAX = 60.0
    const val SWIPE_MIN = 150.0

    /** Classifies one finger-down → finger-up stroke, or null for a short drag that is neither. */
    fun fromStroke(dx: Double, dy: Double): String? {
        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        return when {
            dist < TAP_MAX -> TAP
            dist < SWIPE_MIN -> null
            kotlin.math.abs(dx) > kotlin.math.abs(dy) -> if (dx > 0) SWIPE_RIGHT else SWIPE_LEFT
            dy > 0 -> SWIPE_DOWN
            else -> SWIPE_UP
        }
    }
}
