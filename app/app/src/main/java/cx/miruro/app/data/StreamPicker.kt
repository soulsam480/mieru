package cx.miruro.app.data

/** One pickable source: audio track + provider + server. */
data class StreamOption(val track: String, val label: String, val stream: ResolvedStream) {
    val subtitleCount: Int get() = stream.subtitles.size
}

/**
 * Turns a `/play` response into the list of sources ExoPlayer can open.
 *
 * The response is already provider → server → stream, so adding another backend later means
 * adding a resolver that produces a [StreamOption] — no UI changes.
 */
object StreamPicker {
    /** Providers in the order we prefer them. Unknown providers go last but are still tried. */
    private val preferred = listOf("kickassanime", "anikoto", "icarus")

    /** Sub before dub, everything else after. */
    private val trackOrder = listOf("ssub", "sub", "dub", "raw")

    fun options(
        play: PlayEnvelope,
        preference: TrackPreference = TrackPreference.SUB
    ): List<StreamOption> {
        val order =
            when (preference) {
                TrackPreference.SUB -> listOf("ssub", "sub", "dub", "raw")
                TrackPreference.DUB -> listOf("dub", "ssub", "sub", "raw")
            }
        val collected = mutableListOf<Pair<Int, StreamOption>>()

        val tracks =
            play.tracks.sortedBy { track ->
                order.indexOf(track.track).let { if (it < 0) 99 else it }
            }

        for (track in tracks) {
            val providers =
                track.providers.sortedBy { provider ->
                    preferred.indexOf(provider.provider).let { if (it < 0) 99 else it }
                }

            for (provider in providers) {
                for (server in provider.servers) {
                    val stream =
                        server.streams.firstOrNull {
                            it.format.equals("hls", true) || it.format.equals("mp4", true)
                        } ?: continue

                    val referer =
                        server.headers.entries
                            .firstOrNull { it.key.equals("Referer", true) }
                            ?.value

                    val headers =
                        buildMap {
                            putAll(server.headers)
                            // The media CDN 403s every segment unless Origin accompanies Referer.
                            // Derive it instead of hardcoding: providers rotate hosts.
                            originOf(referer)?.let { put("Origin", it) }
                        }

                    collected +=
                        order.indexOf(track.track).let { if (it < 0) 99 else it } to
                        StreamOption(
                            track = track.track,
                            label = optionLabel(track.track, provider.provider, server.server),
                            stream =
                                ResolvedStream(
                                    url = stream.url,
                                    mimeHint = stream.format?.lowercase() ?: "hls",
                                    headers = headers,
                                    subtitles = usableSubtitles(provider.subtitles),
                                    provider = provider.provider,
                                    server = server.server
                                )
                        )
                }
            }
        }

        // Preferred track first, then sources that actually ship subtitles.
        return collected
            .sortedWith(compareBy({ it.first }, { -it.second.subtitleCount }))
            .map { it.second }
            .distinctBy { it.stream.url }
    }

    fun pick(
        play: PlayEnvelope,
        preference: TrackPreference = TrackPreference.SUB
    ): ResolvedStream? = options(play, preference).firstOrNull()?.stream

    /** Some providers list subtitle entries that are not subtitle files (icarus: `.jpeg` → 403). */
    private fun usableSubtitles(subtitles: List<Subtitle>): List<Subtitle> =
        subtitles.filter { subtitle ->
            subtitle.file.substringBefore('?').endsWith(".vtt", ignoreCase = true)
        }

    private fun optionLabel(track: String, provider: String, server: String): String =
        "${track.uppercase()} · $provider · $server"

    private fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val u = java.net.URI(url)
            "${u.scheme}://${u.authority}"
        }.getOrNull()
    }
}
