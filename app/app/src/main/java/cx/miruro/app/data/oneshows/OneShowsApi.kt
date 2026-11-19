package cx.miruro.app.data.oneshows

import cx.miruro.app.data.ApiException
import cx.miruro.app.data.Http
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Metadata for the 1shows catalogue. Its own client: the site rejects requests that do not look
 * same-origin, and the Miruro interceptor would point its Referer at the wrong host.
 */
class OneShowsApi {

    private val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Referer", "$SITE/")
                        .header("Accept", "application/json")
                        .header("User-Agent", Http.UA)
                        .build()
                )
            }
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * The trending feed is ids only, so each one needs a detail call. A dead id is dropped instead
     * of failing the whole row.
     */
    suspend fun trending(kind: MediaKind): List<MediaCard> {
        val feed =
            when (kind) {
                MediaKind.TV -> "tv"
                MediaKind.MOVIE -> "movies"
                MediaKind.ANIME -> return emptyList()
            }
        val ids = get("$DB/api/management/trending/$feed", TrendingDto.serializer()).tmdbIds

        return coroutineScope {
            ids.distinct()
                .map { id -> async { runCatching { detail(id, kind).toCard() }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
        }
    }

    suspend fun search(query: String): List<MediaCard> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val page =
            get("$SITE/api/search/query", SearchDto.serializer(), mapOf("query" to trimmed))
        return page.results.mapNotNull { it.toCard() }
    }

    suspend fun detail(id: String, kind: MediaKind): MediaDetail = when (kind) {
        MediaKind.TV -> get("$SITE/api/tv/$id", TvDetailDto.serializer()).toDetail(id)

        MediaKind.MOVIE ->
            get("$SITE/api/movie/$id", MovieDetailDto.serializer()).toDetail(id)

        MediaKind.ANIME -> throw IllegalArgumentException("1shows has no anime catalogue")
    }

    suspend fun season(tvId: String, seasonNumber: Int): List<MediaEpisode> =
        get("$SITE/api/tv/$tvId/season/$seasonNumber", SeasonDetailDto.serializer())
            .episodes
            .map { it.toEpisode() }
            .sortedBy { it.number }

    private suspend fun <T> get(
        url: String,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap()
    ): T = withContext(Dispatchers.IO) {
        val target =
            url.toHttpUrl().newBuilder().apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()

        client.newCall(Request.Builder().url(target).get().build()).execute().use { response ->
            val bytes = response.body.bytes()
            if (!response.isSuccessful) {
                throw ApiException(response.code, bytes.toString(Charsets.UTF_8).take(300))
            }
            OneShowsJson.decodeFromString(serializer, bytes.decodeToString())
        }
    }

    private companion object {
        const val SITE = "https://www.1shows.org"
        const val DB = "https://db.1shows.org"
        const val TIMEOUT_SECONDS = 20L
    }
}
