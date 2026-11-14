package cx.miruro.app.data

/**
 * Next/previous episode **within the list you hand it**. The player always passes the episode list
 * of the season currently on screen, which is what keeps navigation from jumping back to season 1.
 */
internal fun nextEpisodeNumber(episodes: List<Episode>, current: Int): Int? =
    episodes.firstOrNull { it.number > current }?.number

internal fun previousEpisodeNumber(episodes: List<Episode>, current: Int): Int? =
    episodes.lastOrNull { it.number < current }?.number
