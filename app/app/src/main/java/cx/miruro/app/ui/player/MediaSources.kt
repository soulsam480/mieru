package cx.miruro.app.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import cx.miruro.app.data.Http
import cx.miruro.app.data.ResolvedStream
import cx.miruro.app.data.Subtitle

/**
 * Builds an ExoPlayer [MediaSource] that carries the provider's required request headers.
 *
 * Subtitles must go through [DefaultMediaSourceFactory]: it wraps the HLS/progressive source in a
 * `MergingMediaSource` with the side-loaded files. Constructing `HlsMediaSource` by hand silently
 * drops `MediaItem.subtitleConfigurations`, which is exactly why no subtitle tracks appeared.
 */
internal object MediaSources {

    fun create(stream: ResolvedStream): MediaSource {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setDefaultRequestProperties(stream.headers)
            .setAllowCrossProtocolRedirects(true)

        val mediaItem =
            MediaItem.Builder()
                .setUri(stream.url)
                .setMimeType(
                    if (stream.isProgressive) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8
                )
                .setSubtitleConfigurations(stream.subtitles.map(::subtitleConfiguration))
                .build()

        return DefaultMediaSourceFactory(dataSource).createMediaSource(mediaItem)
    }

    private fun subtitleConfiguration(subtitle: Subtitle): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.file))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(subtitle.language)
            .setLabel(subtitle.label ?: subtitle.language)
            .setSelectionFlags(if (subtitle.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
}
