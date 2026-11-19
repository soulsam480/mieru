package cx.miruro.app.data.subtitles

import cx.miruro.app.data.Http
import cx.miruro.app.data.Subtitle
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Side-loaded subtitles from OpenSubtitles' public Stremio addon. It needs no key, takes the IMDb
 * id the catalogue already carries, and answers with plain .srt URLs.
 */
object OpenSubtitles {

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every language the addon has for this title, English first. An unreachable addon yields an
     * empty list: subtitles are a bonus, never a reason to fail playback.
     */
    suspend fun search(imdbId: String, season: Int?, episode: Int?): List<Subtitle> =
        withContext(Dispatchers.IO) {
            val path =
                if (season != null && episode != null) {
                    "series/$imdbId:$season:$episode"
                } else {
                    "movie/$imdbId"
                }
            val request =
                Request.Builder()
                    .url("$BASE/subtitles/$path.json")
                    .header("User-Agent", Http.UA)
                    .build()

            val payload =
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        json.decodeFromString<Envelope>(
                            response.body?.string().orEmpty()
                        )
                    }
                }
                    .getOrNull() ?: return@withContext emptyList()

            payload.subtitles
                .mapNotNull { entry ->
                    val file = entry.url
                    val language = entry.lang.ifBlank { null }
                    if (file.isBlank() || language == null) return@mapNotNull null
                    Subtitle(
                        language = language,
                        label = entry.fileName.ifBlank { language },
                        file = file,
                        format = "srt",
                        isDefault = language == "eng"
                    )
                }
                .distinctBy { it.file }
                .sortedBy { it.language != "eng" }
                // One track per language: OpenSubtitles has dozens of English releases and the
                // sheet is a language picker, not a release picker.
                .distinctBy { it.language }
                .take(MAX_TRACKS)
        }

    @Serializable
    private data class Envelope(val subtitles: List<Entry> = emptyList())

    @Serializable
    private data class Entry(
        val url: String = "",
        val lang: String = "",
        @SerialName("subtitleFileName") val fileName: String = ""
    )

    private const val BASE = "https://opensubtitles-v3.strem.io"
    private const val TIMEOUT_SECONDS = 12L
    private const val MAX_TRACKS = 40
}
