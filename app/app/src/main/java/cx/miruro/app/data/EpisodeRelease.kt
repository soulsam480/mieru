package cx.miruro.app.data

import java.time.LocalDate

/**
 * Which episodes of a season actually exist yet.
 *
 * Providers pad the list: a show claiming 24 episodes can have 7 aired, a few with future air
 * dates, and the rest as blank placeholder rows with no air date at all. Treating "no date" as
 * "released" made those placeholders look playable and the list look random.
 *
 * Rules, in order:
 * 1. An episode with an air date on or before today is released.
 * 2. An episode with no air date is released only if a later-numbered one has already aired
 *    (i.e. it sits inside the aired run).
 * 3. If the season has no air dates at all, every episode counts as released — better to allow
 *    playback than to lock a show whose metadata we simply do not have.
 */
internal fun releasedEpisodeNumbers(
    episodes: List<Episode>,
    today: LocalDate = LocalDate.now()
): Set<Int> {
    if (episodes.isEmpty()) return emptySet()

    val dated = episodes.filter { it.airedOn != null }
    if (dated.isEmpty()) return episodes.map { it.number }.toSet()

    val lastAired =
        dated
            .filter { !it.isAfterToday(today) }
            .maxOfOrNull { it.number }

    return episodes
        .filter { episode ->
            val date = episode.releaseDate()
            when {
                date != null -> !date.isAfter(today)
                lastAired != null -> episode.number <= lastAired
                else -> false
            }
        }
        .map { it.number }
        .toSet()
}

private fun Episode.isAfterToday(today: LocalDate): Boolean {
    val date = releaseDate() ?: return false
    return date.isAfter(today)
}

/** Rows the provider invented to pad the season: no air date, no duration, generic title. */
internal fun Episode.hasRealSchedule(): Boolean {
    if (releaseDate() != null) return true
    // Padded rows also carry a duration, so only a real title proves the episode exists.
    val name = title?.trim().orEmpty()
    if (name.isEmpty()) return false
    return !PLACEHOLDER_TITLE.matches(name)
}

/** "Episode 57" on a row that is episode 9 — the provider reuses absolute numbering in the title. */
internal fun Episode.displayTitle(): String? {
    val name = title?.trim().orEmpty()
    if (name.isEmpty()) return null
    if (PLACEHOLDER_TITLE.matches(name)) return null
    return name
}

private val PLACEHOLDER_TITLE = Regex("^Episode \\d+$", RegexOption.IGNORE_CASE)
