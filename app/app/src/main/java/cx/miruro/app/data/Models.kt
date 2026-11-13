package cx.miruro.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------- catalog ----------

@Serializable
data class Titles(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null
) {
    fun best(): String = english ?: romaji ?: native ?: "Untitled"
}

@Serializable
data class EpisodeCounts(val raw: Int? = null, val sub: Int? = null, val dub: Int? = null)

@Serializable
data class AiredSeason(val name: String? = null, val number: Int? = null)

@Serializable
data class NextAiring(
    @SerialName("episode_number") val episodeNumber: Double? = null,
    @SerialName("air_at") val airAt: String? = null
)

@Serializable
data class Anime(
    val id: String,
    val title: Titles = Titles(),
    val format: String? = null,
    val status: String? = null,
    val season: String? = null,
    @SerialName("season_year") val seasonYear: Int? = null,
    @SerialName("average_score") val averageScore: Int? = null,
    val popularity: Int? = null,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("background_url") val backgroundUrl: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("episode_counts") val episodeCounts: EpisodeCounts = EpisodeCounts(),
    @SerialName("next_airing") val nextAiring: NextAiring? = null,
    @SerialName("show_cluster_position") val clusterPosition: Int? = null,
    @SerialName("aired_season") val airedSeason: AiredSeason? = null
) {
    val shownEpisodes: Int?
        get() = episodeCounts.sub ?: episodeCounts.raw ?: episodeCount
}

@Serializable
data class CatalogPage(
    val data: List<Anime> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
data class ShowEnvelope(
    val data: List<Anime> = emptyList(),
    @SerialName("order_release") val orderRelease: List<String> = emptyList()
)

// ---------- episodes ----------

@Serializable
data class Episode(
    @SerialName("episode_number") val episodeNumber: Double = 0.0,
    @SerialName("absolute_number") val absoluteNumber: Double? = null,
    val title: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("aired_on") val airedOn: String? = null,
    @SerialName("skip_times") val skipTimes: List<JsonElement> = emptyList(),
    val kind: String? = null
) {
    val number: Int get() = episodeNumber.toInt()

    /** False when the air date is still in the future (or unknown but flagged as upcoming). */
    fun isReleased(today: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
        val date =
            airedOn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                ?: return true
        return !date.isAfter(today)
    }

    fun releaseDate(): java.time.LocalDate? =
        airedOn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
}

@Serializable
data class EpisodesEnvelope(
    val data: List<Episode> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false
)

// ---------- playback ----------

@Serializable
data class Subtitle(
    val language: String? = null,
    val label: String? = null,
    val file: String,
    val format: String? = null,
    @SerialName("default") val isDefault: Boolean = false
)

@Serializable
data class Thumbnails(val vtt: String? = null, val sprite: String? = null)

@Serializable
data class Embed(val url: String? = null)

@Serializable
data class Stream(
    val url: String,
    val format: String? = null,
    val quality: String? = null,
    val resolution: String? = null,
    val codec: String? = null
)

@Serializable
data class Server(
    val server: String,
    val headers: Map<String, String> = emptyMap(),
    val streams: List<Stream> = emptyList(),
    val embed: Embed? = null
)

@Serializable
data class Provider(
    val provider: String,
    val subtitles: List<Subtitle> = emptyList(),
    val thumbnails: Thumbnails? = null,
    val servers: List<Server> = emptyList()
)

@Serializable
data class Track(val track: String, val providers: List<Provider> = emptyList())

@Serializable
data class PlayEnvelope(
    @SerialName("episode_number") val episodeNumber: Double = 0.0,
    @SerialName("requested_track") val requestedTrack: String? = null,
    val tracks: List<Track> = emptyList()
)

/** A stream the app can actually hand to ExoPlayer, with the headers it must carry. */
data class ResolvedStream(
    val url: String,
    val mimeHint: String,
    val headers: Map<String, String>,
    val subtitles: List<Subtitle>,
    val provider: String,
    val server: String
) {
    val isProgressive: Boolean
        get() = mimeHint == "mp4" || url.contains(".mp4", ignoreCase = true)
}

// ---------- local progress ----------

@Serializable
data class EpisodeProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L
) {
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

@Serializable
data class ContinueItem(
    val animeId: String,
    val title: String,
    val cover: String? = null,
    val episode: Int = 1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class ProgressData(
    /** key = "$animeId:$episodeNumber" */
    val episodes: Map<String, EpisodeProgress> = emptyMap(),
    /** key = animeId, newest last-watched episode per show */
    val recents: Map<String, ContinueItem> = emptyMap()
)

/** One airing from /api/v1/schedule — the source of the "Latest episodes" feed. */
@Serializable
data class AiringEntry(
    @SerialName("anime_id") val animeId: String,
    @SerialName("air_at") val airAt: String,
    @SerialName("episode_number") val episodeNumber: Double = 0.0,
    val track: String? = null
) {
    val episode: Int get() = episodeNumber.toInt()
}

@Serializable
data class ScheduleEnvelope(
    val data: List<AiringEntry> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false
)
