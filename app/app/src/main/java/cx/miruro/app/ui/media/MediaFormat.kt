package cx.miruro.app.ui.media

import cx.miruro.app.data.oneshows.MediaCard
import cx.miruro.app.data.oneshows.MediaKind
import java.util.Locale

internal fun kindTitle(kind: MediaKind): String = when (kind) {
    MediaKind.TV -> "TV Shows"
    MediaKind.MOVIE -> "Movies"
    MediaKind.ANIME -> "Anime"
}

internal fun cardMeta(card: MediaCard): String =
    listOfNotNull(card.year?.toString(), scoreText(card.score)).joinToString(" · ")

internal fun scoreText(score: Double?): String? =
    score?.takeIf { it > 0 }?.let { String.format(Locale.US, "★ %.1f", it) }

internal fun runtimeText(minutes: Int?): String? = minutes?.takeIf { it > 0 }?.let { "$it min" }
