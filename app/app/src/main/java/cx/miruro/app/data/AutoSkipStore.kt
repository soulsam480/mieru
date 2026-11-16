package cx.miruro.app.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Playback behaviours the user opted into. Kept as JSON so the file stays readable. */
@Serializable
data class AutoSkipSettings(val introAndOutro: Boolean = false)

/** Tiny JSON-file store for the auto-skip preference. Mirrors [StyleStore], same pattern. */
class AutoSkipStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    private var loaded = false
    private var settings = AutoSkipSettings()

    fun settings(): AutoSkipSettings = synchronized(lock) {
        if (!loaded) {
            settings =
                if (file.exists()) {
                    runCatching { json.decodeFromString<AutoSkipSettings>(file.readText()) }
                        .getOrDefault(AutoSkipSettings())
                } else {
                    AutoSkipSettings()
                }
            loaded = true
        }
        settings
    }

    fun saveSettings(updated: AutoSkipSettings) {
        synchronized(lock) {
            settings = updated
            loaded = true
        }
        runCatching { file.writeText(json.encodeToString(AutoSkipSettings.serializer(), updated)) }
    }
}
