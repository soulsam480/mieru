package cx.miruro.app

import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.StreamOption
import cx.miruro.app.data.StreamPicker
import cx.miruro.app.data.decodePayload
import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end proof of the app's data path, without a device:
 * decode → catalog → search → paging → show → episodes → play → pick sources → playlist → segment.
 *
 * Hits the live API on purpose. Run with:
 *     gradle :app:testDebugUnitTest
 */
class PayloadTest {

    @Test
    fun decodes_a_real_recorded_play_response() {
        val raw = javaClass.classLoader!!.getResourceAsStream("play.bin")!!.use { it.readBytes() }
        val json = decodePayload(raw)

        assertTrue("payload should decode to JSON", json.trimStart().startsWith("{"))
        assertTrue("should contain tracks", json.contains("\"tracks\""))
        assertTrue("should contain providers", json.contains("\"providers\""))
        assertTrue("should contain a stream url", json.contains("\"url\""))
    }
}

class LiveApiTest {

    private val api = MiruroApi()

    /** No miruro interceptors: media CDNs need the *provider's* headers, not ours. */
    private val http = okhttp3.OkHttpClient()

    private fun fetch(url: String, headers: Map<String, String>): Pair<Int, String> {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        http.newCall(builder.build()).execute().use { response ->
            return response.code to (response.body.string())
        }
    }

    private fun resolve(base: String, child: String): String = when {
        child.startsWith("//") -> "https:$child"
        child.startsWith("http") -> child
        else -> URI(base).resolve(child).toString()
    }

    @Test(timeout = 60_000)
    fun catalog_and_search(): Unit = runBlocking {
        val page = api.catalog(limit = 15, sort = "-popularity")
        assertTrue("catalog returned nothing", page.data.isNotEmpty())
        assertTrue(page.data.first().id.isNotBlank())
        assertTrue(page.data.first().title.best().isNotBlank())

        val search = api.catalog(limit = 15, q = "dandadan")
        assertTrue("search returned nothing", search.data.isNotEmpty())
        assertTrue(
            "search results should be relevant",
            search.data.any { it.title.best().contains("dan", ignoreCase = true) }
        )
    }

    @Test(timeout = 60_000)
    fun genre_browse(): Unit = runBlocking {
        val page = api.catalog(limit = 15, genres = listOf("Sci-Fi"), sort = "-score")
        assertTrue("genre browse returned nothing", page.data.isNotEmpty())
    }

    @Test(timeout = 60_000)
    fun pagination_uses_cursor(): Unit = runBlocking {
        val first = api.catalog(limit = 15, sort = "-popularity")
        val cursor = first.nextCursor
        assertNotNull("first page should expose a cursor", cursor)

        val second = api.catalog(limit = 15, sort = "-popularity", cursor = cursor)
        assertTrue("second page returned nothing", second.data.isNotEmpty())
        assertTrue(
            "second page should not repeat the first",
            second.data.first().id != first.data.first().id
        )
    }

    @Test(timeout = 60_000)
    fun seasons_are_discoverable(): Unit = runBlocking {
        val anime = api.catalog(limit = 15, q = "attack on titan").data.first()
        val show = api.show(anime.id)

        assertTrue("order_release should list the franchise", show.orderRelease.size > 1)

        val franchise = api.animeByIds(show.orderRelease)
        assertEquals("every id should hydrate", show.orderRelease.size, franchise.size)
        assertTrue("should contain several series", franchise.count { it.format == "TV" } >= 2)
    }

    /** The whole point of the app: turn an anime id into a playing HLS stream. */
    @Test(timeout = 120_000)
    fun full_watch_chain_resolves_and_plays(): Unit = runBlocking {
        val anime = api.catalog(limit = 15, sort = "-popularity").data.first()

        val show = api.show(anime.id)
        assertEquals(anime.id, show.data.first().id)

        val episodes = api.episodes(anime.id)
        assertTrue("no episodes", episodes.data.isNotEmpty())

        val play = api.play(anime.id, episodes.data.first().number)
        val options = StreamPicker.options(play)
        assertTrue("no sources resolved", options.isNotEmpty())

        options.forEach { option ->
            assertTrue(
                "every source needs a Referer (${option.label})",
                option.stream.headers.keys.any { it.equals("Referer", ignoreCase = true) }
            )
        }

        // Providers differ call to call, so try them the way the app does: first one that works.
        val playable = firstPlayable(options)
        assertNotNull("no source played; tried ${options.take(6).map { it.label }}", playable)
        playable!!

        val masterUrl = playable.stream.url
        val (masterCode, master) = fetch(masterUrl, playable.stream.headers)
        assertEquals("master playlist should be 200", 200, masterCode)
        assertTrue("master playlist should be m3u8", master.startsWith("#EXTM3U"))
        assertTrue("master playlist should list renditions", master.contains("#EXT-X-STREAM-INF"))

        val variantPath = master.lineSequence().firstOrNull {
            it.isNotBlank() && !it.startsWith("#")
        }
        assertNotNull("no variant playlist", variantPath)

        val variantUrl = resolve(masterUrl, variantPath!!.trim())
        val (variantCode, variant) = fetch(variantUrl, playable.stream.headers)
        assertEquals("variant playlist should be 200", 200, variantCode)
        assertTrue(variant.contains("#EXTINF"))

        val segmentPath = variant.lineSequence().firstOrNull {
            it.isNotBlank() &&
                !it.startsWith("#")
        }
        assertNotNull("no segment", segmentPath)

        val segmentUrl = resolve(variantUrl, segmentPath!!.trim())
        val (segmentCode, segment) = fetch(segmentUrl, playable.stream.headers)
        assertTrue(
            "segment should be fetchable but was $segmentCode",
            segmentCode == 200 || segmentCode == 206
        )
        assertTrue("segment should carry bytes", segment.isNotEmpty())
    }

    @Test(timeout = 120_000)
    fun subtitle_files_are_real_vtt(): Unit = runBlocking {
        val anime = api.catalog(limit = 15, sort = "-popularity").data.first()
        val episode = api.episodes(anime.id).data.first().number
        val play = api.play(anime.id, episode)

        val withSubtitles = StreamPicker.options(play).filter { it.subtitleCount > 0 }
        assertTrue("no source exposed subtitles", withSubtitles.isNotEmpty())

        // CDNs rate-limit, so take the first subtitle from the first few sources that answers.
        var sawVtt = false
        for (option in withSubtitles.take(3)) {
            val subtitle = option.stream.subtitles.first()
            val (code, body) = fetch(subtitle.file, option.stream.headers)
            if (code == 200 && body.contains("WEBVTT")) {
                sawVtt = true
                break
            }
        }
        assertTrue("no subtitle file returned WEBVTT", sawVtt)
    }

    private fun firstPlayable(options: List<StreamOption>): StreamOption? {
        for (option in options.take(6)) {
            val (code, body) = fetch(option.stream.url, option.stream.headers)
            if (code == 200 && body.startsWith("#EXTM3U")) return option
        }
        return null
    }
}
