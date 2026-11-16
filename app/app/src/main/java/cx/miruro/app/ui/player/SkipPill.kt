package cx.miruro.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cx.miruro.app.data.SkipTime
import cx.miruro.app.data.windowAt

/**
 * The "Skip Intro" / "Skip Outro" pill. It is drawn outside the auto-hiding chrome so it stays
 * visible while the rest of the controls are gone.
 *
 * The ticker updates the position four times a second, so [positionMs] is read here instead of in
 * [PlayerScreen]: only this pill recomposes, not the whole player.
 */
@Composable
internal fun SkipPill(
    windows: List<SkipTime>,
    positionMs: () -> Long,
    onClick: (SkipTime) -> Unit,
    modifier: Modifier = Modifier
) {
    if (windows.isEmpty()) return
    val window = windows.windowAt(positionMs()) ?: return

    Box(modifier) {
        PlayerPill(window.label) { onClick(window) }
    }
}

/** Keeps the automatic seek to one per window per episode, so it never loops. */
internal class SkipAutoSkipper {
    private var episode: Int = Int.MIN_VALUE
    private val consumed = mutableSetOf<Long>()

    /** True only the first time [window] is seen for [episode]. */
    fun consume(episode: Int, window: SkipTime): Boolean {
        if (episode != this.episode) {
            this.episode = episode
            consumed.clear()
        }
        return consumed.add(window.startMs)
    }
}
