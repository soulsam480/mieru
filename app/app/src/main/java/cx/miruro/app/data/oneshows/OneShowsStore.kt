package cx.miruro.app.data.oneshows

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
private data class OneShowsConfig(val site: String = "")

/**
 * Where the 1shows catalogue lives. One URL, because the site's own deployment decides its
 * subdomains: the trending list is served by the `db.` host, so the app derives it from whichever
 * site is configured.
 */
class OneShowsStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private var loaded = false
    private var config = OneShowsConfig()

    fun site(): String = synchronized(lock) {
        ensureLoaded()
        config.site.ifBlank { DEFAULT_SITE }
    }

    /** Returns false for anything that is not a usable http(s) base. */
    fun setSite(url: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val normalized = normalize(url) ?: return false
        config = OneShowsConfig(normalized)
        persist()
        true
    }

    fun reset() = synchronized(lock) {
        ensureLoaded()
        config = OneShowsConfig()
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        config =
            if (file.exists()) {
                runCatching { json.decodeFromString<OneShowsConfig>(file.readText()) }
                    .getOrDefault(OneShowsConfig())
            } else {
                OneShowsConfig()
            }
        loaded = true
    }

    private fun persist() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(OneShowsConfig.serializer(), config))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_SITE = "https://www.1shows.org"

        /** Accepts "host", "host/path" and full URLs; anything without a host is refused. */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme =
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    trimmed
                } else {
                    "https://$trimmed"
                }
            val uri = runCatching { withScheme.toHttpUrl() }.getOrNull() ?: return null
            if (uri.host.isBlank()) return null
            return uri.newBuilder().build().toString().trimEnd('/')
        }

        fun deriveDatabase(site: String): String {
            val url = runCatching { site.toHttpUrl() }.getOrNull() ?: return "https://db.1shows.org"
            val host = url.host.removePrefix("www.")
            return "${url.scheme}://db.$host"
        }
    }
}
