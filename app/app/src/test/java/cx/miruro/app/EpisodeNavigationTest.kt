package cx.miruro.app

import cx.miruro.app.data.Episode
import cx.miruro.app.data.nextEpisodeNumber
import cx.miruro.app.data.previousEpisodeNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The player only ever holds the episode list of the season on screen, so these functions must
 * stay inside it — that is the actual guarantee behind "next episode stays in season 2".
 */
class EpisodeNavigationTest {

    private fun season(start: Int, count: Int, offset: Int = 0) = (start until start + count).map {
        Episode(episodeNumber = (it + offset).toDouble(), kind = "regular")
    }

    @Test
    fun next_and_previous_stay_inside_the_given_season() {
        val seasonTwo = season(start = 1, count = 12, offset = 12) // absolute 13..24, season 2

        assertEquals(14.0.toInt(), nextEpisodeNumber(seasonTwo, 13)!!)
        assertEquals(13, previousEpisodeNumber(seasonTwo, 14)!!)
    }

    @Test
    fun the_last_episode_has_no_next_and_the_first_has_no_previous() {
        val seasonTwo = season(start = 1, count = 12, offset = 12)

        assertNull(nextEpisodeNumber(seasonTwo, 24))
        assertNull(previousEpisodeNumber(seasonTwo, 13))
    }

    @Test
    fun a_single_episode_season_has_no_neighbours() {
        val film = listOf(Episode(episodeNumber = 1.0, kind = "regular"))

        assertNull(nextEpisodeNumber(film, 1))
        assertNull(previousEpisodeNumber(film, 1))
    }
}
