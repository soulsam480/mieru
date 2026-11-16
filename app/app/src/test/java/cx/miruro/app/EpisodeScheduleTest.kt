package cx.miruro.app

import cx.miruro.app.data.Episode
import cx.miruro.app.data.displayTitle
import cx.miruro.app.data.hasRealSchedule
import cx.miruro.app.data.releasedEpisodeNumbers
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real shape of a padded season (Apothecary Diaries S3): 7 aired, 5 with a genuine upcoming
 * date, and 12 blank placeholder rows that still claim a 24-minute duration.
 */
class EpisodeScheduleTest {

    private val today = LocalDate.of(2026, 11, 16)

    private fun aired(number: Int) =
        Episode(episodeNumber = number.toDouble(), airedOn = "2026-10-01", durationSeconds = 1500)

    private fun upcoming(number: Int, date: String) =
        Episode(episodeNumber = number.toDouble(), airedOn = date, durationSeconds = 1500)

    private fun placeholder(number: Int) =
        Episode(episodeNumber = number.toDouble(), airedOn = null, durationSeconds = 1440)

    private fun paddedSeason(): List<Episode> = (1..7).map(::aired) +
        listOf(
            upcoming(8, "2026-11-20"),
            upcoming(9, "2026-11-27"),
            upcoming(10, "2026-12-04"),
            upcoming(11, "2026-12-11"),
            upcoming(12, "2026-12-18")
        ) +
        (13..24).map(::placeholder)

    @Test
    fun only_aired_episodes_count_as_released() {
        assertEquals((1..7).toSet(), releasedEpisodeNumbers(paddedSeason(), today))
    }

    @Test
    fun the_list_keeps_aired_and_dated_rows_and_drops_blank_placeholders() {
        val visible = paddedSeason().filter { it.hasRealSchedule() }

        assertEquals(12, visible.size)
        assertEquals((1..12).toSet(), visible.map { it.number }.toSet())
    }

    @Test
    fun a_season_without_any_dates_is_not_locked() {
        val undated = (1..5).map { Episode(episodeNumber = it.toDouble()) }

        assertEquals((1..5).toSet(), releasedEpisodeNumbers(undated, today))
    }

    @Test
    fun placeholder_titles_are_not_shown() {
        assertNull(Episode(episodeNumber = 9.0, title = "Episode 57").displayTitle())
        assertEquals("The Bet", Episode(episodeNumber = 9.0, title = "The Bet").displayTitle())
        assertFalse(placeholder(13).hasRealSchedule())
        assertTrue(upcoming(8, "2026-11-20").hasRealSchedule())
    }
}
