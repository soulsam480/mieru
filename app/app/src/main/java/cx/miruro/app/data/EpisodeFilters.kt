package cx.miruro.app.data

/**
 * Seasons come back with every entry of the show, including specials whose episode numbers are
 * synthetic (20008, 20009…). They are not part of the run, so they are dropped — unless the show
 * has nothing else, in which case everything is kept.
 */
internal fun regularEpisodes(episodes: List<Episode>): List<Episode> {
    val regular = episodes.filter { it.kind == null || it.kind == "regular" }
    return regular.ifEmpty { episodes }
}
