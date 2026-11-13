package cx.miruro.app.ui.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * ExoPlayer swallows non-fatal failures (a subtitle that 403s, a segment that times out) unless
 * someone listens for them. This turns those into logcat lines under `MiruroPlayer`.
 */
internal object PlaybackDiagnostics {
    private const val TAG = "MiruroPlayer"
    private const val VERBOSE = true

    fun logSubtitles(stream: cx.miruro.app.data.ResolvedStream) {
        Log.d(
            TAG,
            "attaching ${stream.subtitles.size} subtitle(s) from ${stream.provider}: " +
                stream.subtitles.take(3).joinToString { "${it.language}(${it.label})" }
        )
    }

    fun attach(player: ExoPlayer) {
        if (!VERBOSE && !Log.isLoggable(TAG, Log.DEBUG)) return

        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean
                ) {
                    Log.w(
                        TAG,
                        "load failed track=${trackName(
                            mediaLoadData.trackType
                        )} uri=${loadEventInfo.uri} " +
                            "reason=${error.javaClass.simpleName}: ${error.message}"
                    )
                }

                override fun onDownstreamFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    mediaLoadData: MediaLoadData
                ) {
                    if (mediaLoadData.trackType == C.TRACK_TYPE_TEXT) {
                        Log.d(
                            TAG,
                            "text track loaded: ${mediaLoadData.trackFormat?.language} " +
                                "${mediaLoadData.trackFormat?.label}"
                        )
                    }
                }
            }
        )
    }

    private fun trackName(trackType: Int): String = when (trackType) {
        C.TRACK_TYPE_TEXT -> "text"
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_VIDEO -> "video"
        else -> "other"
    }
}
