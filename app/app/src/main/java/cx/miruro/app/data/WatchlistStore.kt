package cx.miruro.app.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One saved title. [kind] is "ANIME", "TV" or "MOVIE", matching [ContinueItem]. */
@Serializable
data class WatchlistItem(
    val id: String,
    val kind: String,
    val title: String,
    val poster: String? = null,
    val addedAt: Long = 0L
)

@Serializable
private data class WatchlistData(val items: List<WatchlistItem> = emptyList())

/**
 * Titles the viewer saved. One small JSON file, newest first, written atomically. Same shape as the
 * other stores so a save is a save regardless of which catalogue the title came from.
 */
class WatchlistStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private var loaded = false
    private var data = WatchlistData()

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change so a list can redraw without polling the file. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun items(): List<WatchlistItem> = synchronized(lock) {
        ensureLoaded()
        data.items.sortedByDescending { it.addedAt }
    }

    fun contains(id: String, kind: String): Boolean = synchronized(lock) {
        ensureLoaded()
        data.items.any { it.id == id && it.kind == kind }
    }

    /** Adds or removes, returning true when the title ended up saved. */
    fun toggle(item: WatchlistItem): Boolean = synchronized(lock) {
        ensureLoaded()
        val existing = data.items.any { it.id == item.id && it.kind == item.kind }
        data =
            if (existing) {
                data.copy(items = data.items.filterNot { it.id == item.id && it.kind == item.kind })
            } else {
                data.copy(
                    items =
                        (data.items + item.copy(addedAt = System.currentTimeMillis()))
                            .takeLast(MAX_ITEMS)
                )
            }
        persist()
        _revision.value++
        !existing
    }

    private fun ensureLoaded() {
        if (loaded) return
        data =
            if (file.exists()) {
                runCatching { json.decodeFromString<WatchlistData>(file.readText()) }
                    .getOrDefault(WatchlistData())
            } else {
                WatchlistData()
            }
        loaded = true
    }

    private fun persist() {
        val payload = synchronized(lock) { json.encodeToString(WatchlistData.serializer(), data) }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val MAX_ITEMS = 300
    }
}
