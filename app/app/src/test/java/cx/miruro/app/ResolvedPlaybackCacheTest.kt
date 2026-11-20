package cx.miruro.app

import cx.miruro.app.data.embed.ResolvedEmbed
import cx.miruro.app.data.embed.ResolvedPlaybackCache
import cx.miruro.app.data.embed.SourceStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedPlaybackCacheTest {

    private fun key(episode: Int, season: Int = 1) =
        ResolvedPlaybackCache.Key("5920", "TV", season, episode)

    private fun hit(label: String) = ResolvedPlaybackCache.Hit(
        sources = listOf(
            SourceStream(label, ResolvedEmbed("https://cdn/x.m3u8", emptyMap(), label))
        ),
        caption = "S1 E1",
        untried = emptyList()
    )

    @Test
    fun a_refused_playlist_only_evicts_its_own_episode() {
        val cache = ResolvedPlaybackCache
        val now = System.currentTimeMillis()
        cache.put(key(1, season = 2), hit("one"), now = now)
        cache.put(key(2, season = 2), hit("two"), now = now)

        cache.remove(key(1, season = 2))

        assertNull(cache.get(key(1, season = 2)))
        assertNotNull(cache.get(key(2, season = 2)))
    }

    @Test
    fun the_oldest_resolve_is_dropped_past_the_cap() {
        val cache = ResolvedPlaybackCache
        val base = System.currentTimeMillis()
        repeat(30) { episode ->
            cache.put(key(episode, season = 3), hit("m$episode"), now = base + episode)
        }

        assertNull(cache.get(key(0, season = 3), now = base + 31))
        assertNotNull(cache.get(key(29, season = 3), now = base + 31))
    }
}
