package cx.miruro.app.data

import java.io.File
import kotlinx.coroutines.Dispatchers
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
            loaded = true
        }
    }

    fun snapshot(): ProgressData {
        ensureLoaded()
        return data
    }

    fun positionOf(animeId: String, episode: Int): Long =
        snapshot().episodes[key(animeId, episode)]?.positionMs ?: 0L

    fun progressOf(animeId: String, episode: Int): EpisodeProgress? =
        snapshot().episodes[key(animeId, episode)]

    /** Newest first, skipping anything essentially finished. */
    fun continueWatching(limit: Int = 12): List<ContinueItem> = snapshot()
        .recents.values
        .filter { it.durationMs <= 0L || it.positionMs.toFloat() / it.durationMs < 0.95f }
        .sortedByDescending { it.updatedAt }
        .take(limit)

    suspend fun record(
        animeId: String,
        title: String,
        cover: String?,
        episode: Int,
        positionMs: Long,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val episodes = data.episodes.toMutableMap()
            episodes[key(animeId, episode)] = EpisodeProgress(positionMs, durationMs, now)

            val recents = data.recents.toMutableMap()
            // Only move the "continue watching" pointer forward, never backwards.
            val existing = recents[animeId]
            if (existing == null || episode >= existing.episode) {
                recents[animeId] =
                    ContinueItem(
                        animeId = animeId,
                        title = title,
                        cover = cover,
                        episode = episode,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = now
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

    private fun key(animeId: String, episode: Int) = "$animeId:$episode"
}
