package cx.miruro.app

import cx.miruro.app.data.WatchlistItem
import cx.miruro.app.data.WatchlistStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistStoreTest {

    private fun tempFile(name: String) = File(Files.createTempDirectory("watchlist").toFile(), name)

    private val anime = WatchlistItem(id = "abc", kind = "ANIME", title = "Show", poster = null)

    @Test
    fun toggling_saves_then_removes() {
        val store = WatchlistStore(tempFile("watchlist.json"))

        assertTrue(store.toggle(anime))
        assertTrue(store.contains("abc", "ANIME"))
        assertFalse(store.toggle(anime))
        assertFalse(store.contains("abc", "ANIME"))
    }

    @Test
    fun the_same_id_in_another_kind_is_a_different_title() {
        val store = WatchlistStore(tempFile("watchlist.json"))
        store.toggle(WatchlistItem(id = "1", kind = "TV", title = "Show"))
        store.toggle(WatchlistItem(id = "1", kind = "MOVIE", title = "Film"))

        assertEquals(2, store.items().size)
        assertTrue(store.contains("1", "TV"))
        assertTrue(store.contains("1", "MOVIE"))
    }

    @Test
    fun newest_first_and_persisted() {
        val file = tempFile("watchlist.json")
        val store = WatchlistStore(file)
        store.toggle(WatchlistItem(id = "1", kind = "ANIME", title = "First"))
        Thread.sleep(5)
        store.toggle(WatchlistItem(id = "2", kind = "ANIME", title = "Second"))

        assertEquals(listOf("Second", "First"), store.items().map { it.title })
        assertEquals(listOf("Second", "First"), WatchlistStore(file).items().map { it.title })
    }

    @Test
    fun revision_moves_on_every_change() {
        val store = WatchlistStore(tempFile("watchlist.json"))
        val before = store.revision.value
        store.toggle(anime)
        assertEquals(before + 1, store.revision.value)
    }
}
