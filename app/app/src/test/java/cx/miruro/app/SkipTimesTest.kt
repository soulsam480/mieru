package cx.miruro.app

import cx.miruro.app.data.SkipTime
import cx.miruro.app.data.parseSkipTimes
import cx.miruro.app.data.windowAt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `skip_times` is raw API JSON, so parsing has to be total and forgiving. */
class SkipTimesParsingTest {

    private fun raw(json: String): List<JsonElement> = Json.parseToJsonElement(json).jsonArray

    @Test
    fun reads_the_windows_the_api_publishes() {
        val windows =
            parseSkipTimes(
                raw(
                    """
                    [
                      {"kind": "op", "start_seconds": 25.235, "end_seconds": 33.478},
                      {"kind": "ed", "start_seconds": 1400.5, "end_seconds": 1440.0}
                    ]
                    """.trimIndent()
                )
            )

        assertEquals(2, windows.size)
        assertEquals("op", windows[0].kind)
        assertEquals(25_235L, windows[0].startMs)
        assertEquals(33_478L, windows[0].endMs)
        assertTrue(windows[0].isIntro)
        assertFalse(windows[0].isOutro)

        assertEquals("ed", windows[1].kind)
        assertEquals(1_400_500L, windows[1].startMs)
        assertEquals(1_440_000L, windows[1].endMs)
        assertTrue(windows[1].isOutro)
        assertFalse(windows[1].isIntro)
    }

    @Test
    fun an_empty_array_has_no_windows() {
        assertTrue(parseSkipTimes(emptyList()).isEmpty())
        assertTrue(parseSkipTimes(raw("[]")).isEmpty())
    }

    @Test
    fun ignores_entries_that_are_not_objects() {
        val windows =
            parseSkipTimes(
                raw(
                    """[null, 12, "op", [],
                    {"kind": "op", "start_seconds": 1, "end_seconds": 2}]"""
                )
            )

        assertEquals(1, windows.size)
        assertEquals("op", windows[0].kind)
    }

    @Test
    fun drops_entries_with_missing_or_broken_numbers() {
        val windows =
            parseSkipTimes(
                raw(
                    """
                    [
                      {"kind": "op"},
                      {"kind": "op", "start_seconds": 5},
                      {"kind": "op", "start_seconds": "abc", "end_seconds": 10},
                      {"kind": "op", "start_seconds": 5, "end_seconds": null},
                      {"start_seconds": 5, "end_seconds": 10}
                    ]
                    """.trimIndent()
                )
            )

        assertTrue(windows.isEmpty())
    }

    @Test
    fun drops_windows_that_do_not_move_forward() {
        val windows =
            parseSkipTimes(
                raw(
                    """
                    [
                      {"kind": "op", "start_seconds": 40, "end_seconds": 40},
                      {"kind": "ed", "start_seconds": 40, "end_seconds": 30}
                    ]
                    """.trimIndent()
                )
            )

        assertTrue(windows.isEmpty())
    }

    @Test
    fun keeps_prefixed_kinds_and_drops_unknown_ones() {
        val windows =
            parseSkipTimes(
                raw(
                    """
                    [
                      {"kind": "mixed-op", "start_seconds": 1, "end_seconds": 2},
                      {"kind": "recap", "start_seconds": 1, "end_seconds": 2}
                    ]
                    """.trimIndent()
                )
            )

        assertEquals(listOf("mixed-op"), windows.map { it.kind })
        assertTrue(windows[0].isIntro)
    }
}

/** Window maths decides whether the pill shows and whether the auto-skip fires. */
class SkipWindowTest {

    @Test
    fun classifies_plain_and_prefixed_intro_markers() {
        assertTrue(SkipTime("op", 0, 1_000).isIntro)
        assertTrue(SkipTime("mixed-op", 0, 1_000).isIntro)
        assertFalse(SkipTime("mixed-op", 0, 1_000).isOutro)
    }

    @Test
    fun classifies_plain_and_prefixed_outro_markers() {
        assertTrue(SkipTime("ed", 0, 1_000).isOutro)
        assertTrue(SkipTime("mixed-ed", 0, 1_000).isOutro)
        assertFalse(SkipTime("mixed-ed", 0, 1_000).isIntro)
    }

    @Test
    fun labels_match_the_end_of_the_episode() {
        assertEquals("Skip Intro", SkipTime("mixed-op", 0, 1_000).label)
        assertEquals("Skip Outro", SkipTime("ed", 0, 1_000).label)
    }

    @Test
    fun includes_the_start_but_not_the_end() {
        val window = SkipTime("op", 25_235, 33_478)

        assertFalse(window.contains(25_234))
        assertTrue(window.contains(25_235))
        assertTrue(window.contains(30_000))
        assertTrue(window.contains(33_477))
        assertFalse(window.contains(33_478))
    }

    @Test
    fun finds_the_window_under_the_playhead() {
        val windows = listOf(SkipTime("op", 0, 5_000), SkipTime("ed", 140_000, 145_000))

        assertEquals("op", windows.windowAt(0)?.kind)
        assertEquals("ed", windows.windowAt(144_999)?.kind)
        assertNull(windows.windowAt(60_000))
    }
}
