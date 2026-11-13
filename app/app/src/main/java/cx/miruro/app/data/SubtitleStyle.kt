package cx.miruro.app.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How cues are drawn over the video. Kept as names so the JSON stays readable. */
@Serializable
data class SubtitleStyle(
    val size: String = "M",
    val color: String = "White",
    val background: String = "Dim",
    val outline: Boolean = true
) {
    val textSizeSp: Float
        get() = when (size) {
            "S" -> 15f
            "L" -> 26f
            "XL" -> 32f
            else -> 20f
        }

    val textColorArgb: Int
        get() = when (color) {
            "Yellow" -> 0xFFFFEB3B.toInt()
            "Cyan" -> 0xFF80DEEA.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    /** 0 = fully transparent (no plate behind the text). */
    val backgroundArgb: Int
        get() = when (background) {
            "None" -> 0x00000000
            "Solid" -> 0xCC000000.toInt()
            else -> 0x80000000.toInt()
        }
}

/** Tiny JSON-file store for user preferences. Mirrors [ProgressStore] so there is one pattern. */
class StyleStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    private var loaded = false
    private var style = SubtitleStyle()

    fun subtitleStyle(): SubtitleStyle = synchronized(lock) {
        if (!loaded) {
            style =
                if (file.exists()) {
                    runCatching { json.decodeFromString<SubtitleStyle>(file.readText()) }
                        .getOrDefault(SubtitleStyle())
                } else {
                    SubtitleStyle()
                }
            loaded = true
        }
        style
    }

    fun saveSubtitleStyle(updated: SubtitleStyle) {
        synchronized(lock) {
            style = updated
            loaded = true
        }
        runCatching { file.writeText(json.encodeToString(SubtitleStyle.serializer(), updated)) }
    }
}
