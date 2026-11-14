package cx.miruro.app.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cx.miruro.app.data.AiringEntry
import cx.miruro.app.data.Anime

@Composable
internal fun AnimeCard(anime: Anime, airing: AiringEntry?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            AsyncImage(
                model = anime.coverUrl,
                contentDescription = anime.title.best(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            anime.title.best(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight
        )
        Text(
            airing?.let { "EP ${it.episode} · ${relativeAired(it.airAt)}" } ?: metaLine(anime),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (airing != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun metaLine(anime: Anime): String = buildList {
    anime.format?.let { add(if (it == "TV_SHORT") "TV" else it) }
    anime.seasonYear?.let { add(it.toString()) }
    anime.shownEpisodes?.let { add("$it ep") }
    anime.averageScore?.takeIf { it > 0 }?.let { add("★ $it") }
}.joinToString(" · ")

/** "2h ago" / "3d ago" for the Latest feed. */
private fun relativeAired(airAt: String): String {
    val aired =
        runCatching { java.time.Instant.parse(airAt) }.getOrNull() ?: return "new"
    val minutes = java.time.Duration.between(aired, java.time.Instant.now()).toMinutes()
    return when {
        minutes <= 0L -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}
