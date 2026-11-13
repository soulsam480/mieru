package cx.miruro.app.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class ApiException(val code: Int, val detail: String) : IOException("HTTP $code · $detail")

class MiruroApi(private val endpoints: EndpointStore = EndpointStore.inMemory()) {

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

    /**
     * The edge answers 403 unless the request looks same-origin, so the headers are derived from
     * whichever base URL is configured — that is what makes mirrors work.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val base = endpoints.baseUrl()
                val request =
                    chain.request().newBuilder()
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Referer", "$base/")
                        .header("Accept", "*/*")
                        .header("User-Agent", Http.UA)
                        .build()
                chain.proceed(request)
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun baseUrl(): String = endpoints.baseUrl()

    fun revision(): Int = endpoints.revision

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = (endpoints.baseUrl() + "/health").toHttpUrl()
            client.newCall(Request.Builder().url(url).get().build()).execute().use {
                it.isSuccessful
            }
        }.getOrDefault(false)
    }

    private suspend fun raw(path: String, query: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val url =
                (endpoints.baseUrl() + path).toHttpUrl().newBuilder().apply {
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build()

            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val bytes = response.body.bytes()
                if (!response.isSuccessful) {
                    throw ApiException(response.code, bytes.toString(Charsets.UTF_8).take(300))
                }
                decodePayload(bytes)
            }
        }

    private suspend fun <T> get(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        query: Map<String, String> = emptyMap()
    ): T = json.decodeFromString(serializer, raw(path, query))

    suspend fun catalog(
        limit: Int = 15,
        sort: String = "-popularity",
        q: String? = null,
        genres: List<String> = emptyList(),
        status: String? = null,
        format: String? = null,
        cursor: String? = null
    ): CatalogPage {
        val query =
            buildMap {
                put("limit", limit.toString())
                put("sort", sort)
                q?.takeIf { it.isNotBlank() }?.let { put("q", it.trim()) }
                if (genres.isNotEmpty()) put("genre_all", genres.joinToString(","))
                status?.let { put("status", it) }
                format?.let { put("format", it) }
                cursor?.let { put("cursor", it) }
            }
        return get("/api/v1/anime", CatalogPage.serializer(), query)
    }

    suspend fun show(animeId: String): ShowEnvelope =
        get("/api/v1/anime/$animeId/show", ShowEnvelope.serializer())

    /** Hydrates a list of ids in one call — used for the season switcher. */
    suspend fun animeByIds(ids: List<String>): List<Anime> {
        if (ids.isEmpty()) return emptyList()
        val query =
            mapOf(
                "id_in" to ids.joinToString(","),
                "limit" to "200"
            )
        return get("/api/v1/anime", CatalogPage.serializer(), query).data
    }

    /** Episodes that aired in the last [daysBack] days, newest first, one row per show. */
    suspend fun recentlyAired(daysBack: Long = 7): List<AiringEntry> {
        val today = java.time.LocalDate.now()
        val query =
            mapOf(
                "from" to today.minusDays(daysBack).toString(),
                "to" to today.toString(),
                "track" to "raw,sub",
                "limit" to "10000"
            )
        val envelope = get("/api/v1/schedule", ScheduleEnvelope.serializer(), query)
        return envelope.data
            .sortedByDescending { it.airAt }
            .distinctBy { it.animeId }
    }

    /**
     * The endpoint returns every entry of the season, including specials whose episode numbers are
     * synthetic (20008, 20009…). They are not part of the run, so they are dropped — unless the
     * show has nothing else, in which case everything is kept.
     */
    suspend fun episodes(animeId: String): EpisodesEnvelope {
        val envelope =
            get(
                "/api/v1/anime/$animeId/episodes",
                EpisodesEnvelope.serializer(),
                mapOf("limit" to "10000")
            )
        return envelope.copy(data = regularEpisodes(envelope.data))
    }

    suspend fun play(animeId: String, episode: Int): PlayEnvelope = get(
        "/api/v1/anime/$animeId/episodes/$episode/play",
        PlayEnvelope.serializer()
    )
}
