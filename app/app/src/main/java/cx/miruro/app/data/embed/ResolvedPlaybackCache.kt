package cx.miruro.app.data.embed

import cx.miruro.app.data.Subtitle

/**
 * A provider page that may hand back a playlist. The app resolves these one at a time, so the
 * first working mirror starts playback and the rest arrive as extra sources.
 */
data class MirrorSource(val label: String, val url: String)

/** A playlist that resolved, with the mirror that served it. */
data class SourceStream(val label: String, val stream: ResolvedEmbed)

/**
 * Resolved playlists keyed by what was played. Mirrors sign their URLs and they expire, so entries
 * are dropped after [TTL_MS] and the mirror is asked again.
 */
object ResolvedPlaybackCache {
    data class Key(val mediaId: String, val kind: String, val season: Int, val episode: Int)

    data class Hit(
        val sources: List<SourceStream>,
        val caption: String,
        /** Mirrors that did not get a turn yet. */
        val untried: List<MirrorSource>,
        val subtitles: List<Subtitle> = emptyList()
    )

    private const val TTL_MS = 20 * 60 * 1000L
    private const val MAX_ENTRIES = 24
    private val entries = HashMap<Key, Pair<Long, Hit>>()

    fun get(key: Key, now: Long = System.currentTimeMillis()): Hit? {
        val (resolvedAt, hit) = entries[key] ?: return null
        if (now - resolvedAt > TTL_MS) {
            entries.remove(key)
            return null
        }
        return hit
    }

    fun put(key: Key, hit: Hit, now: Long = System.currentTimeMillis()) {
        entries[key] = now to hit
        // Time-bounded, not size-bounded, until now: a long binge could hold every episode's
        // playlist plus its four mirrors. Keep only the most recent resolves.
        if (entries.size > MAX_ENTRIES) {
            entries.entries
                .sortedBy { it.value.first }
                .take(entries.size - MAX_ENTRIES)
                .forEach { entries.remove(it.key) }
        }
    }

    /** Records a playlist the player resolved in the background and retires its mirror. */
    fun addSource(key: Key, source: SourceStream) {
        val (resolvedAt, hit) = entries[key] ?: return
        if (hit.sources.any { it.label == source.label }) return
        entries[key] =
            resolvedAt to
            hit.copy(
                sources = hit.sources + source,
                untried = hit.untried.filterNot { it.label == source.label }
            )
    }

    /** A refused playlist is stale for that episode only; other titles keep their resolves. */
    fun remove(key: Key) {
        entries.remove(key)
    }
}
