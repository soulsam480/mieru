package cx.miruro.app.ui.player

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import cx.miruro.app.data.ResolvedStream

/**
 * Owns the ExoPlayer instance and publishes the small slice of playback state the UI needs.
 * The composable never talks to ExoPlayer directly.
 */
@Stable
internal class PlayerController(context: Context) {
    val player: ExoPlayer =
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }

    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var videoGroups by mutableStateOf<List<Tracks.Group>>(emptyList())
        private set
    var textGroups by mutableStateOf<List<Tracks.Group>>(emptyList())
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var videoAspectRatio by mutableStateOf(16f / 9f)
        private set

    /** Return true when the failure was handled (e.g. switched to another source). */
    var onPlaybackFailure: (PlaybackException) -> Boolean = { false }

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio =
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!onPlaybackFailure(error)) {
                    lastError =
                        "${error.errorCodeName}: ${error.message?.take(140) ?: "playback failed"}"
                }
            }
        }

    fun attach() {
        player.addListener(listener)
    }

    fun detach() {
        player.removeListener(listener)
    }

    fun open(stream: ResolvedStream, startAtMs: Long, metadata: MediaMetadata? = null) {
        lastError = null
        PlaybackDiagnostics.logSubtitles(stream)
        player.setMediaSource(MediaSources.create(stream, metadata))
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setPreferredTextLanguage(PREFERRED_SUBTITLE_LANGUAGE)
                .setSelectUndeterminedTextLanguage(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        player.prepare()
        if (startAtMs > MIN_RESUME_MS) {
            player.seekTo(startAtMs)
        }
        player.playWhenReady = true
    }

    /** Called from a UI ticker; cheap enough to run twice a second. */
    fun refreshPosition() {
        positionMs = player.currentPosition.coerceAtLeast(0L)
        val reported = player.duration
        if (reported > 0) {
            durationMs = reported
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0L, maxOf(durationMs, 0L)))
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
    }

    fun useVideoGroup(group: Tracks.Group?) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .apply {
                    if (group == null) {
                        clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    } else {
                        setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    }
                }.build()
    }

    fun useTextGroup(group: Tracks.Group?) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, group == null)
                .apply {
                    if (group != null) {
                        setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    }
                }.build()
    }

    fun clearError() {
        lastError = null
    }

    fun release() {
        detach()
        player.release()
    }

    private companion object {
        const val PREFERRED_SUBTITLE_LANGUAGE = "en"
        const val MIN_RESUME_MS = 1_500L
    }
}
