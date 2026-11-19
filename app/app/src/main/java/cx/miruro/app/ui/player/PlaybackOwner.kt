package cx.miruro.app.ui.player

/**
 * The one controller allowed to play. Navigation can leave a player composed for a moment while
 * another is created, and two video streams at once is a bug the viewer hears immediately.
 */
internal object PlaybackOwner {
    private var active: PlayerController? = null

    fun claim(controller: PlayerController) {
        val previous = active
        if (previous !== controller && previous != null) {
            android.util.Log.w("PlaybackOwner", "pausing a player that outlived its screen")
            previous.player.pause()
        }
        active = controller
    }

    fun release(controller: PlayerController) {
        if (active === controller) active = null
    }
}
