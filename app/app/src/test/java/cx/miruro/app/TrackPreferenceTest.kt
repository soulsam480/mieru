package cx.miruro.app

import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.PlayEnvelope
import cx.miruro.app.data.Provider
import cx.miruro.app.data.ResolvedStream
import cx.miruro.app.data.Server
import cx.miruro.app.data.Stream
import cx.miruro.app.data.StreamPicker
import cx.miruro.app.data.Subtitle
import cx.miruro.app.data.Track
import cx.miruro.app.data.TrackPreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sub/Dub preference decides which track is offered first. */
class TrackPreferenceTest {

    private fun subtitle(language: String) =
        Subtitle(language = language, file = "https://x/$language.vtt")

    private fun provider(name: String, subs: Int) = Provider(
        provider = name,
        subtitles = List(subs) { subtitle("en") },
        servers =
            listOf(
                Server(
                    server = "Vid",
                    headers = mapOf("Referer" to "https://example.org/"),
                    streams = listOf(Stream(url = "https://cdn/$name.m3u8", format = "hls"))
                )
            )
    )

    private fun envelope() = PlayEnvelope(
        tracks =
            listOf(
                Track(track = "dub", providers = listOf(provider("dubprov", subs = 0))),
                Track(track = "ssub", providers = listOf(provider("subprov", subs = 3)))
            )
    )

    @Test
    fun sub_preference_picks_the_subtitled_track_first() {
        val first = StreamPicker.options(envelope(), TrackPreference.SUB).first()
        assertEquals("ssub", first.track)
        assertTrue(first.subtitleCount > 0)
    }

    @Test
    fun dub_preference_picks_the_dub_track_first() {
        val first = StreamPicker.options(envelope(), TrackPreference.DUB).first()
        assertEquals("dub", first.track)
    }

    @Test
    fun every_option_keeps_the_referer_header() {
        StreamPicker.options(
            envelope(),
            TrackPreference.SUB
        ).forEach { option: cx.miruro.app.data.StreamOption ->
            assertTrue(option.stream is ResolvedStream)
            assertTrue(option.stream.headers.keys.any { it.equals("Referer", true) })
        }
    }
}

/** The Latest feed must not surface raw-only airings. */
class LatestFeedTest {

    @Test(timeout = 60_000)
    fun latest_feed_contains_no_raw_airings(): Unit = runBlocking {
        val airings = MiruroApi().recentlyAired()
        assertTrue("feed was empty", airings.isNotEmpty())
        assertTrue(
            "raw airings leaked into the feed: ${airings.map { it.track }.distinct()}",
            airings.all { it.track == null || it.track == "sub" }
        )
    }
}
