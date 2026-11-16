package cx.miruro.app.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One Miruro-compatible server. */
@Serializable
data class Source(val id: String, val name: String, val baseUrl: String)

@Serializable
private data class SourceList(
    val sources: List<Source> = emptyList(),
    val selectedId: String? = null
)

/**
 * The servers the app can talk to. One is selected at a time; the rest are fallbacks the user can
 * switch to from Settings when a host moves, blocks, or dies.
 *
 * The old single-endpoint file is migrated automatically, so upgrading users keep their host.
 */
class SourceStore(private val file: File, private val legacyFile: File? = null) {

    private companion object {
        const val DEFAULT_ID = "miruro"
        const val DEFAULT_NAME = "Miruro"
        const val DEFAULT_BASE_URL = "https://www.miruro.cx"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    private var loaded = false
    private var state = SourceList()

    /** Bumped on every change so screens know their data is stale. */
    @Volatile
    var revision: Int = 0
        private set

    fun sources(): List<Source> = synchronized(lock) {
        ensureLoaded()
        state.sources
    }

    fun selected(): Source = synchronized(lock) {
        ensureLoaded()
        state.sources.firstOrNull { it.id == state.selectedId } ?: state.sources.first()
    }

    fun baseUrl(): String = selected().baseUrl

    fun select(id: String) = synchronized(lock) {
        ensureLoaded()
        if (state.sources.none { it.id == id }) return
        state = state.copy(selectedId = id)
        revision += 1
        persist()
    }

    /** Adds and selects a new source. Returns null when the URL is unusable. */
    fun add(name: String, rawUrl: String): Source? {
        val base = normalise(rawUrl)
        if (base.isBlank()) return null

        synchronized(lock) {
            ensureLoaded()
            val source =
                Source(
                    id = "src-${System.currentTimeMillis()}",
                    name = name.trim().ifBlank { hostOf(base) },
                    baseUrl = base
                )
            state =
                state.copy(
                    sources = state.sources + source,
                    selectedId = source.id
                )
            revision += 1
            persist()
            return source
        }
    }

    /** Renames/rescopes an existing source. */
    fun update(id: String, name: String, rawUrl: String): Boolean {
        val base = normalise(rawUrl)
        if (base.isBlank()) return false

        synchronized(lock) {
            ensureLoaded()
            if (state.sources.none { it.id == id }) return false
            state =
                state.copy(
                    sources =
                        state.sources.map { source ->
                            if (source.id == id) {
                                source.copy(
                                    name = name.trim().ifBlank { hostOf(base) },
                                    baseUrl = base
                                )
                            } else {
                                source
                            }
                        }
                )
            revision += 1
            persist()
            return true
        }
    }

    /** Removes a source. The last one cannot be removed. */
    fun remove(id: String): Boolean {
        synchronized(lock) {
            ensureLoaded()
            if (state.sources.size <= 1) return false
            if (state.sources.none { it.id == id }) return false

            val remaining = state.sources.filterNot { it.id == id }
            state =
                state.copy(
                    sources = remaining,
                    selectedId =
                        if (state.selectedId == id) remaining.first().id else state.selectedId
                )
            revision += 1
            persist()
            return true
        }
    }

    /** Back to a single default source. */
    fun reset() = synchronized(lock) {
        state =
            SourceList(
                sources = listOf(Source(DEFAULT_ID, DEFAULT_NAME, DEFAULT_BASE_URL)),
                selectedId = DEFAULT_ID
            )
        loaded = true
        revision += 1
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return

        val stored =
            if (file.exists()) {
                runCatching { json.decodeFromString<SourceList>(file.readText()) }.getOrNull()
            } else {
                null
            }

        state =
            when {
                stored != null && stored.sources.isNotEmpty() -> stored
                else -> migratedOr(default())
            }
        loaded = true
    }

    private fun default() = SourceList(
        sources = listOf(Source(DEFAULT_ID, DEFAULT_NAME, DEFAULT_BASE_URL)),
        selectedId = DEFAULT_ID
    )

    /** The previous build stored a single URL in `endpoint.txt`. Keep it as the first source. */
    private fun migratedOr(fallback: SourceList): SourceList {
        val legacy =
            legacyFile
                ?.takeIf { it.exists() }
                ?.let { runCatching { it.readText() }.getOrNull() }
                ?.let { normalise(it) }
                .orEmpty()

        if (legacy.isBlank() || legacy == DEFAULT_BASE_URL) return fallback

        return SourceList(
            sources = listOf(Source(DEFAULT_ID, "Custom", legacy)) + fallback.sources,
            selectedId = DEFAULT_ID
        )
    }

    private fun persist() {
        val payload =
            synchronized(lock) { json.encodeToString(SourceList.serializer(), state) }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }
    }

    private fun normalise(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl).host ?: baseUrl }.getOrNull() ?: baseUrl
}
