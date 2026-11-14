package cx.miruro.app.data

import java.io.File

/** Which audio track to prefer when a provider offers both. */
enum class TrackPreference { SUB, DUB }

/**
 * Remembers whether the user wants subs or dubs. Providers publish both as separate "tracks" in
 * the play response, so this only changes which one is picked first.
 */
class PlaybackPrefsStore(private val file: File) {

    private var cached: TrackPreference? = null

    fun trackPreference(): TrackPreference {
        cached?.let { return it }

        val stored =
            if (file.exists()) {
                runCatching { file.readText().trim() }.getOrNull()
            } else {
                null
            }

        val resolved =
            when (stored?.uppercase()) {
                "DUB" -> TrackPreference.DUB
                else -> TrackPreference.SUB
            }
        cached = resolved
        return resolved
    }

    fun save(preference: TrackPreference) {
        cached = preference
        runCatching { file.writeText(preference.name) }
    }
}
