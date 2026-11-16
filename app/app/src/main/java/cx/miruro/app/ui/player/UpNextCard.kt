package cx.miruro.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown when an episode reaches its end and another one follows. The countdown lives in the
 * caller: this is just the card, so the screen decides when it loads the next episode.
 */
@Composable
internal fun UpNextCard(
    secondsLeft: Int,
    nextEpisode: Int?,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                upNextCountdownLabel(secondsLeft),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (nextEpisode != null) {
                Text(
                    "EP $nextEpisode · starts on its own",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPlayNow) { Text("Play now") }
                TextButton(onClick = onCancel) {
                    Text(
                        "Cancel",
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/** How long the card waits before the next episode starts on its own. */
internal const val UP_NEXT_COUNTDOWN_SECONDS = 5

/** Seconds still to wait after [elapsedTicks] one-second ticks. Never negative. */
internal fun upNextRemainingSeconds(elapsedTicks: Int): Int =
    (UP_NEXT_COUNTDOWN_SECONDS - elapsedTicks).coerceAtLeast(0)

/** "Next episode in 5…" */
internal fun upNextCountdownLabel(secondsLeft: Int): String =
    "Next episode in ${secondsLeft.coerceAtLeast(0)}…"
