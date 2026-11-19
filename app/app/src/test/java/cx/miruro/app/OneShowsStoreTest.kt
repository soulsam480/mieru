package cx.miruro.app

import cx.miruro.app.data.oneshows.OneShowsStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneShowsStoreTest {

    private fun tempFile(name: String) = File(Files.createTempDirectory("oneshows").toFile(), name)

    @Test
    fun defaults_to_the_public_site() {
        val store = OneShowsStore(tempFile("oneshows.json"))

        assertEquals("https://www.1shows.org", store.site())
        assertEquals("https://db.1shows.org", store.database())
    }

    @Test
    fun a_mirror_moves_both_hosts_and_persists() {
        val file = tempFile("oneshows.json")
        val store = OneShowsStore(file)

        assertEquals(true, store.setSite("1shows.example.org"))
        assertEquals("https://1shows.example.org", store.site())
        assertEquals("https://db.1shows.example.org", store.database())
        // a fresh store reads the same file
        assertEquals("https://1shows.example.org", OneShowsStore(file).site())
    }

    @Test
    fun trailing_slashes_and_scheme_are_tolerated() {
        val store = OneShowsStore(tempFile("oneshows.json"))

        assertEquals(true, store.setSite("https://www.1shows.org/"))
        assertEquals("https://www.1shows.org", store.site())
    }

    @Test
    fun junk_is_refused_and_the_old_site_stays() {
        val store = OneShowsStore(tempFile("oneshows.json"))
        store.setSite("https://1shows.example.org")

        assertEquals(false, store.setSite("   "))
        assertEquals("https://1shows.example.org", store.site())
        assertNull(OneShowsStore.normalize("   "))
    }

    @Test
    fun reset_returns_to_the_default() {
        val store = OneShowsStore(tempFile("oneshows.json"))
        store.setSite("https://mirror.example.org")

        store.reset()
        assertEquals("https://www.1shows.org", store.site())
    }
}
