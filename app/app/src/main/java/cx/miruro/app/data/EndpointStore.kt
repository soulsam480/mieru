package cx.miruro.app.data

import java.io.File

/**
 * Where the app talks to. Miruro is a third-party site: it can be renamed, blocked or taken down,
 * so the base URL is a user setting rather than a constant baked into the build.
 *
 * Everything the edge requires (`Sec-Fetch-Site`, `Referer`) is derived from this value at request
 * time, so pointing the app at a mirror "just works".
 */
class EndpointStore(private val file: File) {

    private var cached: String? = null

    /** Bumped on every change so screens can tell they are showing stale data. */
    @Volatile
    var revision: Int = 0
        private set

    fun baseUrl(): String {
        cached?.let { return it }
        val stored =
            if (file.exists()) {
                runCatching { file.readText().trim() }.getOrDefault("")
            } else {
                ""
            }
        val resolved = normalise(stored).ifBlank { DEFAULT_BASE_URL }
        cached = resolved
        return resolved
    }

    fun save(rawUrl: String) {
        val normalised = normalise(rawUrl).ifBlank { DEFAULT_BASE_URL }
        cached = normalised
        revision += 1
        runCatching { file.writeText(normalised) }
    }

    fun reset() = save(DEFAULT_BASE_URL)

    /** "miruro.cx/ " and "https://miruro.cx" both become "https://miruro.cx". */
    private fun normalise(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.miruro.cx"

        /** Tests and previews. */
        fun inMemory(baseUrl: String = DEFAULT_BASE_URL) =
            EndpointStore(File("/dev/null")).apply { save(baseUrl) }
    }
}
