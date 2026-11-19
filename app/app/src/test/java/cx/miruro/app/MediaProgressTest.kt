package cx.miruro.app

import cx.miruro.app.data.ProgressStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProgressTest {

    private fun tempFile(name: String) = File(Files.createTempDirectory("progress").toFile(), name)

    @Test
    fun seasons_get_their_own_position() = runBlocking {
        val store = ProgressStore(tempFile("progress.json"))
        store.record(
            "5920",
            "The Mentalist",
            null,
            episode = 1,
            positionMs = 60_000,
            durationMs = 600_000,
            season = 2,
            kind = "TV"
        )

        assertEquals(60_000, store.positionOf("5920", 2, 1))
        // A different season is a different episode, not the same one.
        assertEquals(0, store.positionOf("5920", 3, 1))
    }

    @Test
    fun anime_positions_keep_their_old_key() = runBlocking {
        val file = tempFile("progress.json")
        val store = ProgressStore(file)
        store.record("abc", "Show", null, episode = 5, positionMs = 1_000, durationMs = 9_000)

        // Reopening reads the same file, so the key format did not change under existing installs.
        assertEquals(1_000, ProgressStore(file).positionOf("abc", 5))
    }

    @Test
    fun continue_watching_is_split_by_section() = runBlocking {
        val store = ProgressStore(tempFile("progress.json"))
        store.record("anime-1", "Anime", null, 1, 1_000, 9_000)
        store.record("movie-1", "Film", null, 1, 2_000, 9_000, kind = "MOVIE")
        store.record("tv-1", "Show", null, 1, 3_000, 9_000, season = 1, kind = "TV")

        assertEquals(listOf("Anime"), store.continueWatching("ANIME").map { it.title })
        assertEquals(listOf("Film"), store.continueWatching("MOVIE").map { it.title })
        assertEquals(listOf("Show"), store.continueWatching("TV").map { it.title })
    }

    @Test
    fun a_later_episode_moves_the_pointer_backwards_never() = runBlocking {
        val store = ProgressStore(tempFile("progress.json"))
        store.record("tv-1", "Show", null, 4, 5_000, 9_000, season = 1, kind = "TV")
        store.record("tv-1", "Show", null, 2, 1_000, 9_000, season = 1, kind = "TV")

        val item = store.continueWatching("TV").single()
        assertEquals(4, item.episode)
        assertTrue(item.season == 1)
    }

    @Test
    fun a_finished_episode_leaves_the_row() = runBlocking {
        val store = ProgressStore(tempFile("progress.json"))
        store.record("movie-1", "Film", null, 1, 9_500, 10_000, kind = "MOVIE")

        assertTrue(store.continueWatching("MOVIE").isEmpty())
    }
}
