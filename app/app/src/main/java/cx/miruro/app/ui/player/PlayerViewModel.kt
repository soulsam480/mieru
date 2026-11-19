package cx.miruro.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cx.miruro.app.data.Episode
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.PlaybackPrefsStore
import cx.miruro.app.data.ProgressStore
import cx.miruro.app.data.ResolvedStream
import cx.miruro.app.data.SkipTime
import cx.miruro.app.data.StreamOption
import cx.miruro.app.data.StreamPicker
import cx.miruro.app.data.embed.EmbedResolver
import cx.miruro.app.data.embed.PlaybackRequest
import cx.miruro.app.data.embed.ResolvedPlaybackCache
import cx.miruro.app.data.embed.SourceStream
import cx.miruro.app.data.embed.asStream
import cx.miruro.app.data.parseSkipTimes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val episode: Int = 1,
    val episodes: List<Episode> = emptyList(),
    /** Intro/outro windows the provider marked for [episode]. Empty when it published none. */
    val skipTimes: List<SkipTime> = emptyList(),
    val options: List<StreamOption> = emptyList(),
    val selected: Int = 0,
    val startPositionMs: Long = 0L,
    val artworkUrl: String? = null,
    val loading: Boolean = true,
    val error: String? = null
) {
    val stream: ResolvedStream? get() = options.getOrNull(selected)?.stream
}

class PlayerViewModel(
    private val api: MiruroApi,
    private val progress: ProgressStore,
    private val prefs: PlaybackPrefsStore,
    private val animeId: String,
    private val animeTitle: String,
    startEpisode: Int,
    /** A stream already resolved elsewhere (an embed page, say): nothing to look up. */
    private val direct: PlaybackRequest? = null
) : ViewModel() {
    private val directStream: ResolvedStream? = direct?.let { it.stream.asStream(it.subtitles) }
    private val _state =
        MutableStateFlow(
            if (direct == null) {
                PlayerUiState(episode = startEpisode)
            } else {
                PlayerUiState(
                    episode = direct.episode,
                    options =
                        listOf(
                            StreamOption(
                                track = "embed",
                                label = direct.stream.provider,
                                stream = directStream ?: direct.stream.asStream()
                            )
                        ),
                    startPositionMs = direct.startMs,
                    loading = false
                )
            }
        )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var cover: String? = null

    init {
        if (direct == null) {
            viewModelScope.launch {
                runCatching { api.show(animeId) }.onSuccess { show ->
                    cover =
                        (
                            show.data.firstOrNull {
                                it.id == animeId
                            } ?: show.data.firstOrNull()
                            )?.coverUrl
                }
            }
            load(startEpisode)
        }
    }

    fun load(episode: Int) {
        _state.update {
            it.copy(
                episode = episode,
                skipTimes = emptyList(),
                loading = true,
                error = null,
                options = emptyList(),
                selected = 0
            )
        }

        viewModelScope.launch {
            if (_state.value.episodes.isEmpty()) {
                runCatching { api.episodes(animeId) }.onSuccess { envelope ->
                    _state.update {
                        it.copy(episodes = envelope.data.sortedBy { e -> e.episodeNumber })
                    }
                }
            }

            val play = runCatching { api.play(animeId, episode) }
            val options =
                play.getOrNull()
                    ?.let { StreamPicker.options(it, prefs.trackPreference()) }
                    .orEmpty()

            // The episode list carries the skip markers; the play response does not.
            val skipTimes =
                parseSkipTimes(
                    _state.value.episodes
                        .firstOrNull { it.number == episode }
                        ?.skipTimes
                        .orEmpty()
                )

            android.util.Log.d(
                "MiruroPlayer",
                "load anime=$animeId ep=$episode ok=${play.isSuccess} options=${options.size} " +
                    "err=${play.exceptionOrNull()?.javaClass?.simpleName}:${play.exceptionOrNull()?.message}"
            )

            _state.update {
                it.copy(
                    options = options,
                    skipTimes = skipTimes,
                    selected = 0,
                    startPositionMs = progress.positionOf(animeId, episode),
                    artworkUrl = cover,
                    loading = false,
                    error =
                        when {
                            options.isNotEmpty() -> null
                            play.exceptionOrNull() != null -> play.exceptionOrNull()?.message
                            else -> "No playable stream for this episode"
                        }
                )
            }
        }
    }

    /** Switch provider/server. Keeps the current playback position when one is supplied. */
    fun select(index: Int, positionMs: Long? = null) {
        if (index !in _state.value.options.indices) return
        _state.update {
            it.copy(
                selected = index,
                startPositionMs = positionMs ?: it.startPositionMs,
                error = null
            )
        }
    }

    /** Called when ExoPlayer reports an error: hop to the next source, if any. */
    fun nextOptionOnFailure(): Boolean {
        val state = _state.value
        val next = state.selected + 1
        if (next > state.options.lastIndex) return false
        android.util.Log.w("MiruroPlayer", "falling back to option $next of ${state.options.size}")
        select(next)
        return true
    }

    /**
     * Resolves the mirrors the detail screen did not try, appending each one as a source. Runs
     * behind playback so the first working mirror is never held up by the rest.
     */
    fun collectSources(context: android.content.Context) {
        val request = direct ?: return
        if (request.alternates.isEmpty()) return

        viewModelScope.launch {
            val resolver = EmbedResolver()
            // Bounded: a long mirror list is not worth a minute of background page loads.
            for (mirror in request.alternates.take(MAX_BACKGROUND_SOURCES)) {
                if (_state.value.options.any { it.label == mirror.label }) continue
                val stream = resolver.resolve(context, mirror.url, mirror.label) ?: continue
                ResolvedPlaybackCache.addSource(
                    request.cacheKey,
                    SourceStream(mirror.label, stream)
                )
                _state.update {
                    it.copy(
                        options =
                            it.options + StreamOption("embed", mirror.label, stream.asStream())
                    )
                }
            }
        }
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L || durationMs <= 0L) return

        if (direct != null) {
            viewModelScope.launch {
                progress.record(
                    animeId = direct.mediaId,
                    title = direct.title,
                    cover = direct.poster,
                    episode = direct.episode,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    season = direct.season,
                    kind = direct.kind
                )
            }
            return
        }

        viewModelScope.launch {
            progress.record(
                animeId = animeId,
                title = animeTitle.ifBlank { "Episode" },
                cover = cover,
                episode = _state.value.episode,
                positionMs = positionMs,
                durationMs = durationMs
            )
        }
    }

    private companion object {
        const val MAX_BACKGROUND_SOURCES = 4
    }

    val nextEpisode: () -> Int? = {
        val episodes = _state.value.episodes
        val current = _state.value.episode
        episodes.firstOrNull { it.number > current }?.number
    }

    val previousEpisode: () -> Int? = {
        val current = _state.value.episode
        _state.value.episodes
            .lastOrNull { it.number < current }
            ?.number
    }
}
