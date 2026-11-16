package cx.miruro.app.data

import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An intro ("op") or outro ("ed") window the provider marked for one episode. Offsets are
 * milliseconds because that is what the player seeks with.
 */
data class SkipTime(val kind: String, val startMs: Long, val endMs: Long) {
    /** Some markers are prefixed ("mixed-op"), so the last segment says which end it belongs to. */
    private val segment: String
        get() = kind.substringAfterLast('-').substringAfterLast('_').lowercase()

    val isIntro: Boolean get() = segment == "op" || segment == "intro"

    val isOutro: Boolean get() = segment == "ed" || segment == "outro"

    /** Button text for the window. Only intro/outro windows survive [parseSkipTimes]. */
    val label: String get() = if (isOutro) "Skip Outro" else "Skip Intro"

    /** Half-open: a position exactly on [endMs] has already left the window. */
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/**
 * Reads the API's `skip_times` array. Entries that are not readable as a positive finite window
 * of a kind the player understands are dropped instead of thrown: a bad marker must never take
 * playback down with it.
 */
internal fun parseSkipTimes(raw: List<JsonElement>): List<SkipTime> = raw.mapNotNull { element ->
    val obj = element as? JsonObject ?: return@mapNotNull null
    val kind = primitiveText(obj["kind"])?.lowercase() ?: return@mapNotNull null
    val start = primitiveText(obj["start_seconds"])?.toDoubleOrNull()
        ?: return@mapNotNull null
    val end = primitiveText(obj["end_seconds"])?.toDoubleOrNull()
        ?: return@mapNotNull null
    if (!start.isFinite() || !end.isFinite()) return@mapNotNull null

    val window =
        SkipTime(
            kind = kind,
            startMs = (start * MILLIS_PER_SECOND).roundToLong(),
            endMs = (end * MILLIS_PER_SECOND).roundToLong()
        )
    window.takeIf { it.endMs > it.startMs && (it.isIntro || it.isOutro) }
}

/** The intro/outro window [positionMs] currently falls in, if any. */
internal fun List<SkipTime>.windowAt(positionMs: Long): SkipTime? =
    firstOrNull { it.contains(positionMs) }

private fun primitiveText(element: JsonElement?): String? = (element as? JsonPrimitive)
    ?.takeIf { it !is JsonNull }
    ?.content
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private const val MILLIS_PER_SECOND = 1000.0
