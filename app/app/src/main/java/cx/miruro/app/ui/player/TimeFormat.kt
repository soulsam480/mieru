package cx.miruro.app.ui.player

/** "1:04:09" or "4:09" — nothing fancier than the player needs. */
internal fun formatPlaybackTime(millis: Long): String {
    if (millis <= 0L) return "0:00"

    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
