package cx.miruro.app.data.oneshows

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class MediaKind { ANIME, TV, MOVIE }

data class MediaCard(
    val id: String,
    val kind: MediaKind,
    val title: String,
    val posterUrl: String? = null,
    val year: Int? = null,
    val score: Double? = null
)

data class MediaDetail(
    val id: String,
    val kind: MediaKind,
    val title: String,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val genres: List<String> = emptyList(),
    val seasons: List<SeasonInfo> = emptyList(),
    val runtimeMinutes: Int? = null
)

data class SeasonInfo(val number: Int, val name: String, val episodeCount: Int)

data class MediaEpisode(
    val number: Int,
    val title: String = "",
    val overview: String = "",
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null
)

internal val OneShowsJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

// ---------- wire ----------

@Serializable
internal data class TrendingDto(
    val success: Boolean = false,
    @SerialName("tmdbIds") val tmdbIds: List<String> = emptyList()
)

@Serializable
internal data class SearchDto(
    val page: Int = 1,
    val results: List<SearchResultDto> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1
)

/** TMDB search rows carry either `title` (movie) or `name` (tv); only `media_type` tells them apart. */
@Serializable
internal data class SearchResultDto(
    val id: Long? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

@Serializable
internal data class GenreDto(val name: String? = null)

@Serializable
internal data class SeasonDto(
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0
)

@Serializable
internal data class TvDetailDto(
    val id: Long? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val genres: List<GenreDto> = emptyList(),
    val seasons: List<SeasonDto> = emptyList()
)

@Serializable
internal data class MovieDetailDto(
    val id: Long? = null,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val runtime: Int? = null,
    val genres: List<GenreDto> = emptyList()
)

@Serializable
internal data class SeasonDetailDto(val episodes: List<EpisodeDto> = emptyList())

@Serializable
internal data class EpisodeDto(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null
)

// ---------- mapping ----------

internal fun SearchResultDto.toCard(): MediaCard? {
    val kind = when (mediaType) {
        "tv" -> MediaKind.TV
        "movie" -> MediaKind.MOVIE
        else -> return null
    }
    val numericId = id ?: return null

    return MediaCard(
        id = numericId.toString(),
        kind = kind,
        title = (name ?: title).orEmpty().trim().ifBlank { UNTITLED },
        posterUrl = thumbUrl(posterPath),
        year = yearOf(firstAirDate ?: releaseDate),
        score = voteAverage
    )
}

internal fun TvDetailDto.toDetail(id: String): MediaDetail = MediaDetail(
    id = id,
    kind = MediaKind.TV,
    title = name.orEmpty().trim().ifBlank { UNTITLED },
    overview = overview.orEmpty().trim(),
    posterUrl = thumbUrl(posterPath),
    backdropUrl = backdropUrl(backdropPath),
    year = yearOf(firstAirDate),
    score = voteAverage,
    genres = genres.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) },
    seasons = seasons.map { it.toSeason() }
)

internal fun MovieDetailDto.toDetail(id: String): MediaDetail = MediaDetail(
    id = id,
    kind = MediaKind.MOVIE,
    title = title.orEmpty().trim().ifBlank { UNTITLED },
    overview = overview.orEmpty().trim(),
    posterUrl = thumbUrl(posterPath),
    backdropUrl = backdropUrl(backdropPath),
    year = yearOf(releaseDate),
    score = voteAverage,
    genres = genres.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) },
    runtimeMinutes = runtime
)

internal fun SeasonDto.toSeason(): SeasonInfo = SeasonInfo(
    number = seasonNumber,
    name = name.orEmpty().trim().ifBlank { "Season $seasonNumber" },
    episodeCount = episodeCount
)

internal fun EpisodeDto.toEpisode(): MediaEpisode = MediaEpisode(
    number = episodeNumber,
    title = name.orEmpty().trim(),
    overview = overview.orEmpty().trim(),
    stillUrl = thumbUrl(stillPath),
    airDate = airDate?.trim()?.takeIf(String::isNotEmpty),
    runtimeMinutes = runtime
)

internal fun MediaDetail.toCard(): MediaCard = MediaCard(
    id = id,
    kind = kind,
    title = title,
    posterUrl = posterUrl,
    year = year,
    score = score
)

private fun thumbUrl(path: String?): String? = imageUrl(path, "w500")

private fun backdropUrl(path: String?): String? = imageUrl(path, "w1280")

private fun imageUrl(path: String?, size: String): String? {
    val value = path?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (value.startsWith("http")) return value
    return "$IMAGES/$size/${value.removePrefix("/")}"
}

private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

private const val IMAGES = "https://image.tmdb.org/t/p"
private const val UNTITLED = "Untitled"
