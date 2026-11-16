package cx.miruro.app

import cx.miruro.app.ui.player.NORMAL_SPEED
import cx.miruro.app.ui.player.PLAYBACK_SPEEDS
import cx.miruro.app.ui.player.formatSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Speed sheet is driven entirely by this list and this formatter. */
class PlaybackSpeedTest {

    @Test
    fun the_sheet_offers_exactly_the_advertised_speeds() {
        assertEquals(
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f),
            PLAYBACK_SPEEDS
        )
    }

    @Test
    fun one_times_is_among_them_and_is_the_default() {
        assertTrue(PLAYBACK_SPEEDS.contains(NORMAL_SPEED))
        assertEquals(1f, NORMAL_SPEED)
    }

    @Test
    fun whole_multipliers_drop_the_decimal_point() {
        assertEquals("1×", formatSpeed(1f))
        assertEquals("2×", formatSpeed(2f))
    }

    @Test
    fun fractional_multipliers_keep_their_digits() {
        assertEquals("0.5×", formatSpeed(0.5f))
        assertEquals("0.75×", formatSpeed(0.75f))
        assertEquals("1.25×", formatSpeed(1.25f))
        assertEquals("1.5×", formatSpeed(1.5f))
        assertEquals("1.75×", formatSpeed(1.75f))
    }

    @Test
    fun every_offered_speed_has_a_label() {
        PLAYBACK_SPEEDS.forEach { speed ->
            assertTrue("no label for $speed", formatSpeed(speed).endsWith("×"))
        }
    }
}
