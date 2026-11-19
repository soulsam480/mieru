package cx.miruro.app.data.embed

/**
 * Hands a resolved stream from a detail screen to the player route. A holder instead of nav
 * arguments because header maps do not survive route encoding cleanly.
 */
object PendingPlayback {
    private var pending: ResolvedEmbed? = null
    private var title: String = ""

    fun offer(stream: ResolvedEmbed, label: String) {
        pending = stream
        title = label
    }

    fun take(): Pair<ResolvedEmbed, String>? {
        val stream = pending ?: return null
        val label = title
        pending = null
        title = ""
        return stream to label
    }
}
