package cx.miruro.app

import cx.miruro.app.ui.player.UP_NEXT_COUNTDOWN_SECONDS
import cx.miruro.app.ui.player.upNextCountdownLabel
import cx.miruro.app.ui.player.upNextRemainingSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

/** The card shows a five-second countdown; these two functions are the whole of it. */
class UpNextCountdownTest {

    @Test
    fun the_countdown_starts_at_five() {
        assertEquals(5, UP_NEXT_COUNTDOWN_SECONDS)
        assertEquals(5, upNextRemainingSeconds(elapsedTicks = 0))
    }

    @Test
    fun every_tick_takes_exactly_one_second_off() {
        assertEquals(4, upNextRemainingSeconds(elapsedTicks = 1))
        assertEquals(3, upNextRemainingSeconds(elapsedTicks = 2))
        assertEquals(2, upNextRemainingSeconds(elapsedTicks = 3))
        assertEquals(1, upNextRemainingSeconds(elapsedTicks = 4))
        assertEquals(0, upNextRemainingSeconds(elapsedTicks = 5))
    }

    @Test
    fun it_never_counts_below_zero() {
        assertEquals(0, upNextRemainingSeconds(elapsedTicks = 9))
        assertEquals(0, upNextRemainingSeconds(elapsedTicks = 100))
    }

    @Test
    fun the_card_reads_as_a_sentence() {
        assertEquals("Next episode in 5…", upNextCountdownLabel(5))
        assertEquals("Next episode in 1…", upNextCountdownLabel(1))
        assertEquals("Next episode in 0…", upNextCountdownLabel(0))
    }
}
