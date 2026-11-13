package cx.miruro.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal fun Context.findActivity(): Activity? {
    var candidate = this
    while (candidate is ContextWrapper) {
        if (candidate is Activity) return candidate
        candidate = candidate.baseContext
    }
    return null
}

/**
 * Immersive playback: hide the system bars and rotate to landscape while [enabled].
 * Always restores the window when the player leaves the screen.
 */
@Composable
internal fun FullscreenEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current

    LaunchedEffect(enabled) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        activity.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val window = activity.window
            WindowCompat.getInsetsController(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
