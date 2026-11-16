package cx.miruro.app

import cx.miruro.app.data.SourceStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStoreTest {

    private fun tempFile(name: String) = File(Files.createTempDirectory("sources").toFile(), name)

    @Test
    fun starts_with_the_default_source() {
        val store = SourceStore(tempFile("sources.json"))

        assertEquals(1, store.sources().size)
        assertEquals("https://www.miruro.cx", store.selected().baseUrl)
    }

    @Test
    fun adding_a_source_selects_it_and_persists() {
        val file = tempFile("sources.json")
        val store = SourceStore(file)

        val added = store.add("Mirror", "mirror.example.org")
        assertNotNull(added)
        assertEquals("https://mirror.example.org", added!!.baseUrl)
        assertEquals(added.id, store.selected().id)

        // a fresh store reads the same file
        val reopened = SourceStore(file)
        assertEquals(2, reopened.sources().size)
        assertEquals("https://mirror.example.org", reopened.selected().baseUrl)
    }

    @Test
    fun rejects_an_empty_url() {
        val store = SourceStore(tempFile("sources.json"))
        assertEquals(null, store.add("Nope", "   "))
    }

    @Test
    fun selects_between_sources() {
        val store = SourceStore(tempFile("sources.json"))
        val added = store.add("Mirror", "https://mirror.example.org")!!
        val default = store.sources().first { it.id != added.id }

        store.select(default.id)
        assertEquals(default.id, store.selected().id)

        store.select(added.id)
        assertEquals(added.id, store.selected().id)
    }

    @Test
    fun the_last_source_cannot_be_removed() {
        val store = SourceStore(tempFile("sources.json"))
        assertFalse(store.remove(store.selected().id))

        val added = store.add("Mirror", "https://mirror.example.org")!!
        assertTrue(store.remove(added.id))
        assertEquals(1, store.sources().size)
        assertFalse("selected source must fall back", store.selected().id == added.id)
    }

    @Test
    fun migrates_the_old_single_endpoint_file() {
        val legacy = tempFile("endpoint.txt").apply { writeText("https://old.example.org") }
        val store = SourceStore(tempFile("sources.json"), legacyFile = legacy)

        assertEquals("https://old.example.org", store.selected().baseUrl)
        assertEquals(2, store.sources().size)
    }

    @Test
    fun reset_returns_to_the_default_source() {
        val store = SourceStore(tempFile("sources.json"))
        store.add("Mirror", "https://mirror.example.org")
        store.reset()

        assertEquals(1, store.sources().size)
        assertEquals("https://www.miruro.cx", store.selected().baseUrl)
    }
}
