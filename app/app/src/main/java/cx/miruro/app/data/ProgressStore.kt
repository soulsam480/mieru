package cx.miruro.app.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Watch-time tracking. Purely local: the web client keeps the same thing in localStorage
 * (`miruro:watching`), there is no server API for it.
 *
 * One small JSON file, cached in memory, written atomically and never more than every few
 * seconds (the player throttles its calls).
 */
class ProgressStore(file: File) {
    private val file = file
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val lock = Any()

    private var loaded = false
    private var data = ProgressData()

    /** Bumped on every write so a screen showing "Continue watching" can refresh itself. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            data =
                if (file.exists()) {
                    runCatching { json.decodeFromString<ProgressData>(file.readText()) }
                        .getOrDefault(ProgressData())
                } else {
                    ProgressData()
                }
            // Recents used to be keyed by media id alone. Re-key once so an upgrading install does
            // not keep a stale card next to every show it starts watching again.
            data =
                data.copy(
                    recents = data.recents.mapKeys { (_, item) ->
                        "${item.kind}:${item.animeId}"
                    }
                )
            loaded = true
        }
    }

    fun snapshot(): ProgressData {
        ensureLoaded()
        return data
    }

    fun positionOf(animeId: String, episode: Int): Long =
        snapshot().episodes[key(animeId, 0, episode)]?.positionMs ?: 0L

    fun positionOf(mediaId: String, season: Int, episode: Int): Long =
        snapshot().episodes[key(mediaId, season, episode)]?.positionMs ?: 0L

    fun progressOf(animeId: String, episode: Int): EpisodeProgress? =
        snapshot().episodes[key(animeId, 0, episode)]

    /** Every section mixed, newest first: the library screen's "continue" list. */
    fun continueWatchingAll(limit: Int = 20): List<ContinueItem> = snapshot()
        .recents.values
        .filter { it.durationMs <= 0L || it.positionMs.toFloat() / it.durationMs < 0.95f }
        .sortedByDescending { it.updatedAt }
        .take(limit)

    /** Newest first, skipping anything essentially finished. */
    fun continueWatching(kind: String = ANIME, limit: Int = 12): List<ContinueItem> = snapshot()
        .recents.values
        .filter { it.kind == kind }
        .filter { it.durationMs <= 0L || it.positionMs.toFloat() / it.durationMs < 0.95f }
        .sortedByDescending { it.updatedAt }
        .take(limit)

    suspend fun record(
        animeId: String,
        title: String,
        cover: String?,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        season: Int = 0,
        kind: String = ANIME
    ) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val episodes = data.episodes.toMutableMap()
            episodes[key(animeId, season, episode)] =
                EpisodeProgress(positionMs, durationMs, now)

            val recents = data.recents.toMutableMap()
            val entry = "$kind:$animeId"
            // Only move the "continue watching" pointer forward, never backwards.
            val existing = recents[entry]
            if (existing == null || season > existing.season ||
                (season == existing.season && episode >= existing.episode)
            ) {
                recents[entry] =
                    ContinueItem(
                        animeId = animeId,
                        title = title,
                        cover = cover,
                        episode = episode,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = now,
                        season = season,
                        kind = kind
                    )
            }

            val trimmed =
                recents.entries
                    .sortedByDescending { it.value.updatedAt }
                    .take(100)
                    .associate { it.key to it.value }

            data = ProgressData(episodes = episodes, recents = trimmed)
        }

        persist()
        _revision.value++
    }

    private fun persist() {
        val payload = synchronized(lock) { json.encodeToString(ProgressData.serializer(), data) }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }
    }

    /**
     * Anime keys predate seasons and are stored as "$id:$episode". Keeping that shape means an
     * existing install keeps its resume positions.
     */
    private fun key(mediaId: String, season: Int, episode: Int) =
        if (season > 0) "$mediaId:$season:$episode" else "$mediaId:$episode"

    private companion object {
        const val ANIME = "ANIME"
    }
}
