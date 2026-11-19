package cx.miruro.app.data.embed

import cx.miruro.app.data.ResolvedStream
import cx.miruro.app.data.Subtitle

/** The playlist as ExoPlayer needs it: headers, cookies and side-loaded tracks carried through. */
fun ResolvedEmbed.asStream(subtitles: List<Subtitle> = emptyList()): ResolvedStream =
    ResolvedStream(
        url = url,
        mimeHint = if (url.contains(".mp4", ignoreCase = true)) "mp4" else "hls",
        headers = headers,
        subtitles = subtitles,
        provider = provider,
        server = "embed"
    )

/**
 * Everything the player needs for a stream the app resolved itself: the playlist, the caption to
 * show, where to start, the mirrors still worth trying, and the catalogue entry to record
 * progress against.
 */
data class PlaybackRequest(
    val stream: ResolvedEmbed,
    val title: String,
    val caption: String,
    val mediaId: String,
    val kind: String,
    val poster: String?,
    val season: Int,
    val episode: Int,
    val startMs: Long = 0L,
    /** Mirrors the player resolves in the background to fill the Source sheet. */
    val alternates: List<MirrorSource> = emptyList(),
    val subtitles: List<Subtitle> = emptyList()
) {
    val cacheKey: ResolvedPlaybackCache.Key
        get() = ResolvedPlaybackCache.Key(mediaId, kind, season, episode)
}
