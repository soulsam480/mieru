package cx.miruro.app.ui.player

import android.graphics.Color as AndroidColor
import android.util.TypedValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import cx.miruro.app.data.SubtitleStyle

/** The video surface itself. No chrome, no gestures — just the texture and the cue renderer. */
@Composable
internal fun PlayerSurface(
    controller: PlayerController,
    style: SubtitleStyle,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val ratio = controller.videoAspectRatio.takeIf { it > 0.1f } ?: DEFAULT_ASPECT_RATIO

        // Cues are anchored to the view, not the picture, so lift them clear of the letterbox bar
        // and of the control bar when it is showing.
        val renderedHeight = minOf(containerHeight, containerWidth / ratio)
        val letterboxFraction =
            ((containerHeight - renderedHeight) / 2f / containerHeight).coerceIn(0f, 0.45f)

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    player = controller.player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setBackgroundColor(AndroidColor.BLACK)
                    subtitleView?.applySubtitleStyle(style, letterboxFraction, controlsVisible)
                }
            },
            update = { playerView ->
                playerView.player = controller.player
                playerView.subtitleView?.applySubtitleStyle(
                    style,
                    letterboxFraction,
                    controlsVisible
                )
            }
        )
    }
}

private fun SubtitleView.applySubtitleStyle(
    style: SubtitleStyle,
    letterbox: Float,
    controlsVisible: Boolean
) {
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(false)
    setStyle(
        CaptionStyleCompat(
            style.textColorArgb,
            style.backgroundArgb,
            AndroidColor.TRANSPARENT,
            if (style.outline) {
                CaptionStyleCompat.EDGE_TYPE_OUTLINE
            } else {
                CaptionStyleCompat.EDGE_TYPE_NONE
            },
            AndroidColor.BLACK,
            null
        )
    )
    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)

    val comfort = if (controlsVisible) CONTROLS_CLEARANCE else IDLE_CLEARANCE
    setBottomPaddingFraction((letterbox + comfort).coerceAtMost(0.60f))
}

private const val DEFAULT_ASPECT_RATIO = 16f / 9f
private const val CONTROLS_CLEARANCE = 0.28f
private const val IDLE_CLEARANCE = 0.19f
