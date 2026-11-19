package cx.miruro.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cx.miruro.app.data.SubtitleStyle
import cx.miruro.app.data.embed.ResolvedEmbed
import kotlinx.coroutines.delay

/**
 * Plays a stream the embed resolver produced. No episode list, no provider switching: those belong
 * to the catalogue the stream came from.
 */
@Composable
fun DirectPlayerScreen(
    stream: ResolvedEmbed,
    title: String,
    style: SubtitleStyle,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val controller = remember { PlayerController(context) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    DisposableEffect(Unit) {
        controller.attach()
        controller.onPlaybackFailure = {
            error = "This provider refused the stream"
            true
        }
        onDispose { controller.release() }
    }

    LaunchedEffect(stream.url) {
        controller.open(
            stream = cx.miruro.app.data.ResolvedStream(
                url = stream.url,
                mimeHint = if (stream.url.contains(".mp4", true)) "mp4" else "hls",
                headers = stream.headers,
                subtitles = emptyList(),
                provider = "embed",
                server = "embed"
            ),
            startAtMs = 0L
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            controller.refreshPosition()
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(controller, style, controlsVisible = false, modifier = Modifier.fillMaxSize())

        Row(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 28.dp, start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "Now playing" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "embed source",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (controller.lastError != null || error != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: controller.lastError ?: "", color = Color.White)
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .clickable { controller.togglePlayPause() },
            contentAlignment = Alignment.Center
        ) {
            if (!controller.isPlaying) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}
