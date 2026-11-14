package cx.miruro.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.PlaybackPrefsStore
import cx.miruro.app.data.ProgressStore
import cx.miruro.app.data.StyleStore
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    api: MiruroApi,
    progress: ProgressStore,
    styles: StyleStore,
    prefs: PlaybackPrefsStore,
    animeId: String,
    animeTitle: String,
    startEpisode: Int,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit
) {
    val vm: PlayerViewModel = viewModel {
        PlayerViewModel(api, progress, prefs, animeId, animeTitle, startEpisode)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val view = LocalView.current

    val controller = remember { PlayerController(context) }
    // Surfaces the episode in the system media panel (quick settings / lock screen).
    val mediaSession =
        remember {
            androidx.media3.session.MediaSession.Builder(context, controller.player).build()
        }
    val chrome = remember { PlayerChromeState() }
    val gestures = remember { GestureController(context) }
    var isFullscreen by remember { mutableStateOf(false) }
    var subtitleStyle by remember { mutableStateOf(styles.subtitleStyle()) }

    BackHandler {
        if (isFullscreen) isFullscreen = false else onBack()
    }
    FullscreenEffect(isFullscreen)

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        controller.attach()
        PlaybackDiagnostics.attach(controller.player)
        controller.onPlaybackFailure = { vm.nextOptionOnFailure() }
        onDispose {
            vm.saveProgress(
                controller.player.currentPosition,
                controller.player.duration.coerceAtLeast(0L)
            )
            view.keepScreenOn = false
            gestures.releaseBrightness()
            mediaSession.release()
            controller.release()
        }
    }

    LaunchedEffect(state.stream, state.startPositionMs, state.episode) {
        val stream = state.stream ?: return@LaunchedEffect
        controller.open(stream, state.startPositionMs, state.mediaMetadata(animeTitle))
        chrome.visible = true
    }

    // No background playback: leaving the app pauses, and the screen brightness goes back
    // to the system.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        controller.player.pause()
        gestures.releaseBrightness()
    }

    LaunchedEffect(state.episode) {
        while (true) {
            controller.refreshPosition()
            delay(TICK_MILLIS)
        }
    }

    LaunchedEffect(state.episode) {
        while (true) {
            delay(PROGRESS_SAVE_MILLIS)
            if (controller.isPlaying) {
                vm.saveProgress(controller.player.currentPosition, controller.player.duration)
            }
        }
    }

    LaunchedEffect(chrome.visible, controller.isPlaying) {
        if (chrome.visible && controller.isPlaying) {
            delay(CHROME_TIMEOUT_MILLIS)
            chrome.visible = false
        }
    }

    LaunchedEffect(gestures.seekFeedback) {
        if (gestures.seekFeedback != null) {
            delay(SEEK_FEEDBACK_MILLIS)
            gestures.clearSeekFeedback()
        }
    }

    LaunchedEffect(gestures.hud) {
        if (gestures.hud != null) {
            delay(HUD_TIMEOUT_MILLIS)
            gestures.clearHud()
        }
    }

    LaunchedEffect(controller.textGroups, chrome.subtitleLanguage) {
        val language = chrome.subtitleLanguage
        if (language == null) {
            controller.useTextGroup(null)
            return@LaunchedEffect
        }

        val matchingGroup = controller.textGroups.firstOrNull { group ->
            (0 until group.length).any { index ->
                group.getTrackFormat(index).language?.lowercase()?.startsWith(language) == true
            }
        }
        android.util.Log.d(
            "MiruroPlayer",
            "subtitle effect: language=$language groups=${controller.textGroups.size} match=${matchingGroup != null}"
        )
        if (matchingGroup != null) {
            controller.useTextGroup(matchingGroup)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .playerGestures(
                gestures = gestures,
                onTap = { chrome.visible = !chrome.visible },
                onDoubleTap = { zone ->
                    when (zone) {
                        ScreenZone.Left -> {
                            controller.seekBy(-SEEK_STEP_MILLIS)
                            gestures.showSeek(-SEEK_STEP_SECONDS)
                        }

                        ScreenZone.Right -> {
                            controller.seekBy(SEEK_STEP_MILLIS)
                            gestures.showSeek(SEEK_STEP_SECONDS)
                        }

                        ScreenZone.Center -> controller.togglePlayPause()
                    }
                }
            )
    ) {
        PlayerSurface(controller, subtitleStyle, chrome.visible, Modifier.fillMaxSize())

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        val failure = state.error ?: controller.lastError
        if (failure != null && state.stream == null) {
            PlaybackFailure(message = failure, onRetry = { vm.load(state.episode) })
        }

        if (chrome.visible) {
            Box(Modifier.align(Alignment.Center)) {
                PlayPauseButton(controller)
            }

            Box(Modifier.align(Alignment.TopCenter)) {
                PlayerTopBar(
                    title = animeTitle,
                    episode = state.episode,
                    provider = state.stream?.provider,
                    isFullscreen = isFullscreen,
                    onBack = onBack,
                    onPreviousEpisode = vm.previousEpisode()?.let { episode ->
                        { vm.load(episode) }
                    },
                    onNextEpisode = vm.nextEpisode()?.let { episode -> { vm.load(episode) } },
                    onToggleFullscreen = { isFullscreen = !isFullscreen },
                    onOpenInfo = onOpenInfo
                )
            }

            Box(Modifier.align(Alignment.BottomCenter)) {
                PlayerBottomBar(
                    controller = controller,
                    chrome = chrome,
                    hasSources = state.options.size > 1,
                    onOpenSheet = { chrome.openSheet = it }
                )
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GestureHudOverlay(gestures.hud)
        }

        SeekFeedbackOverlay(
            feedback = gestures.seekFeedback,
            alignment =
                if ((gestures.seekFeedback?.seconds ?: 0) < 0) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                }
        )
    }

    PlayerSheets(
        controller = controller,
        chrome = chrome,
        options = state.options,
        selectedSource = state.selected,
        onSelectSource = { index ->
            // Keep the position when swapping provider/server mid-episode.
            vm.select(index, controller.player.currentPosition)
        },
        style = subtitleStyle,
        onStyleChange = { updated ->
            subtitleStyle = updated
            styles.saveSubtitleStyle(updated)
        }
    )
}

@Composable
private fun PlaybackFailure(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Cannot play this episode", color = Color.White)
            Text(
                message,
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

private const val TICK_MILLIS = 250L
private const val SEEK_STEP_MILLIS = 10_000L
private const val SEEK_STEP_SECONDS = 10
private const val SEEK_FEEDBACK_MILLIS = 700L
private const val PROGRESS_SAVE_MILLIS = 5_000L
private const val CHROME_TIMEOUT_MILLIS = 4_000L
private const val HUD_TIMEOUT_MILLIS = 800L

/** Big translucent play/pause in the middle, like every other video app. */
@Composable
private fun PlayPauseButton(controller: PlayerController) {
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = controller::togglePlayPause),
        contentAlignment = Alignment.Center
    ) {
        if (controller.isPlaying) {
            PauseGlyph()
        } else {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(42.dp)
            )
        }
    }
}

/** Title / subtitle / artwork for the notification-shade media controls. */
private fun PlayerUiState.mediaMetadata(showTitle: String): androidx.media3.common.MediaMetadata {
    val title = if (showTitle.isBlank()) "Episode $episode" else "$showTitle · EP $episode"
    return androidx.media3.common.MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(stream?.provider?.let { "Mieru · $it" } ?: "Mieru")
        .setArtworkUri(artworkUrl?.let { android.net.Uri.parse(it) })
        .build()
}
