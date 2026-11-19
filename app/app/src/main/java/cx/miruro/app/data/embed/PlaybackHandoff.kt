package cx.miruro.app.data.embed

/**
 * Hands a resolved stream from a detail screen to the player. A holder instead of nav arguments
 * because header maps do not survive route encoding cleanly.
 */
object PlaybackHandoff {
    private var pending: PlaybackRequest? = null

    fun offer(request: PlaybackRequest) {
        pending = request
    }

    /** Removes and returns the pending request: exactly one player may claim it. */
    fun take(): PlaybackRequest? = pending.also { pending = null }
}
