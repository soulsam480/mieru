package cx.miruro.app.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Which third of the screen a vertical drag started in. */
private enum class DragEdge { None, Left, Right }

internal enum class GestureKind { Brightness, Volume }

internal data class GestureHud(val kind: GestureKind, val fraction: Float)

/** Which third of the screen was tapped. */
internal enum class ScreenZone { Left, Center, Right }

/** "−10s" / "+10s" feedback after a double tap. */
internal data class SeekFeedback(val seconds: Int)

/**
 * Netflix-style screen gestures: drag up/down on the left third for brightness, on the right
 * third for volume. Brightness is per-window (it never touches the system setting).
 */
@Stable
internal class GestureController(context: Context) {

    private val activity = context.findActivity()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var hud by mutableStateOf<GestureHud?>(null)
        private set
    var seekFeedback by mutableStateOf<SeekFeedback?>(null)
        private set

    private var dragEdge = DragEdge.None
    private var brightness = activity?.window?.attributes?.screenBrightness
        ?.takeIf { it >= 0f } ?: DEFAULT_BRIGHTNESS

    fun beginDrag(x: Float, screenWidth: Float) {
        dragEdge = when {
            x < screenWidth / 3f -> DragEdge.Left
            x > screenWidth * 2f / 3f -> DragEdge.Right
            else -> DragEdge.None
        }
    }

    /** [deltaFraction] is positive upwards. */
    fun drag(deltaFraction: Float) {
        when (dragEdge) {
            DragEdge.Left -> adjustBrightness(deltaFraction)
            DragEdge.Right -> adjustVolume(deltaFraction)
            DragEdge.None -> Unit
        }
    }

    fun endDrag() {
        dragEdge = DragEdge.None
    }

    fun clearHud() {
        hud = null
    }

    fun showSeek(seconds: Int) {
        seekFeedback = SeekFeedback(seconds)
    }

    fun clearSeekFeedback() {
        seekFeedback = null
    }

    /** Give the brightness back to the system. Leaving our override in place dims the whole app. */
    fun releaseBrightness() {
        val window = activity?.window ?: return
        window.attributes =
            window.attributes.apply {
                screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
    }

    private fun adjustBrightness(deltaFraction: Float) {
        val window = activity?.window ?: return
        brightness = (brightness + deltaFraction).coerceIn(MIN_BRIGHTNESS, 1f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
        hud = GestureHud(GestureKind.Brightness, brightness)
    }

    private fun adjustVolume(deltaFraction: Float) {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maximum <= 0) return

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + deltaFraction * maximum).roundToInt().coerceIn(0, maximum)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        hud = GestureHud(GestureKind.Volume, target.toFloat() / maximum)
    }

    private companion object {
        const val DEFAULT_BRIGHTNESS = 0.5f
        const val MIN_BRIGHTNESS = 0.02f
    }
}

@Composable
internal fun Modifier.playerGestures(
    gestures: GestureController,
    onTap: () -> Unit,
    onDoubleTap: (ScreenZone) -> Unit
): Modifier {
    // Rebuild the detectors after a rotation: otherwise the zone maths keep using the old bounds.
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    return playerGestureModifier(gestures, onTap, onDoubleTap, orientation)
}

private fun Modifier.playerGestureModifier(
    gestures: GestureController,
    onTap: () -> Unit,
    onDoubleTap: (ScreenZone) -> Unit,
    orientationKey: Int
): Modifier = this
    .pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { offset -> gestures.beginDrag(offset.x, size.width.toFloat()) },
            onDragEnd = { gestures.endDrag() },
            onDragCancel = { gestures.endDrag() }
        ) { change, dragAmount ->
            change.consume()
            // Use the short side so the gesture feels the same in portrait and landscape.
            val span = minOf(size.width, size.height).toFloat().coerceAtLeast(1f)
            gestures.drag(-dragAmount / span)
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { offset ->
                val width = size.width.toFloat()
                onDoubleTap(
                    when {
                        offset.x < width / 3f -> ScreenZone.Left
                        offset.x > width * 2f / 3f -> ScreenZone.Right
                        else -> ScreenZone.Center
                    }
                )
            }
        )
    }

@Composable
internal fun GestureHudOverlay(hud: GestureHud?) {
    if (hud == null) return

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(190.dp)
                .clip(RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                when (hud.kind) {
                    GestureKind.Brightness -> "Brightness"
                    GestureKind.Volume -> "Volume"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            LinearProgressIndicator(
                progress = { hud.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.25f)
            )
        }
    }
}

@Composable
internal fun SeekFeedbackOverlay(feedback: SeekFeedback?, alignment: Alignment) {
    if (feedback == null) return

    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        Text(
            (if (feedback.seconds < 0) "−" else "+") + "${kotlin.math.abs(feedback.seconds)}s",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}
