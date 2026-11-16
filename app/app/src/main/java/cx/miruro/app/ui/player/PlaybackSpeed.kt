package cx.miruro.app.ui.player

/** Speeds offered in the Speed sheet, in menu order. */
internal val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** What playback goes back to on a new episode, and after a 2× hold. */
internal const val NORMAL_SPEED = 1f

/** Speed applied while the finger is held on the video. */
internal const val BOOST_SPEED = 2f

/** "1×", "0.5×", "1.25×" — no trailing zeros, because nobody writes 1.0×. */
internal fun formatSpeed(speed: Float): String {
    val multiplier = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
    return "$multiplier×"
}
