package cx.miruro.app

import cx.miruro.app.data.Episode
import cx.miruro.app.data.isNewerVersion
import cx.miruro.app.data.regularEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules that do not need the network. */
class EpisodeRulesTest {

    @Test
    fun drops_specials_that_are_numbered_outside_the_run() {
        val episodes =
            listOf(
                Episode(episodeNumber = 1.0, kind = "regular"),
                Episode(episodeNumber = 2.0, kind = "regular"),
                Episode(episodeNumber = 20001.0, kind = "special"),
                Episode(episodeNumber = 20002.0, kind = "special")
            )

        val kept = regularEpisodes(episodes)

        assertEquals(2, kept.size)
        assertTrue(kept.all { it.number in 1..2 })
    }

    @Test
    fun keeps_everything_when_a_show_only_has_specials() {
        val onlySpecials = listOf(Episode(episodeNumber = 1.0, kind = "special"))
        assertEquals(1, regularEpisodes(onlySpecials).size)
    }

    @Test
    fun unreleased_episodes_are_flagged() {
        val aired = Episode(episodeNumber = 1.0, airedOn = "2026-01-01")
        val upcoming = Episode(episodeNumber = 2.0, airedOn = "2027-01-01")
        val unknown = Episode(episodeNumber = 3.0, airedOn = null)

        val today = java.time.LocalDate.of(2026, 11, 13)
        assertTrue(aired.isReleased(today))
        assertFalse(upcoming.isReleased(today))
        assertTrue("unknown dates are assumed released", unknown.isReleased(today))
    }
}

class VersionRulesTest {

    @Test
    fun compares_dotted_versions_numerically() {
        assertTrue(isNewerVersion("1.10.0", "1.9.3"))
        assertTrue(isNewerVersion("2.0", "1.9.9"))
        assertFalse(isNewerVersion("1.1", "1.1"))
        assertFalse(isNewerVersion("1.0.9", "1.1"))
    }

    @Test
    fun tolerates_the_tag_prefix() {
        assertTrue(isNewerVersion("v1.2.0", "1.1.0"))
        assertFalse(isNewerVersion("", "1.1.0"))
    }
}
