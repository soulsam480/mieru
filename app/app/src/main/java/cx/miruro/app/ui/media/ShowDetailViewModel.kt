package cx.miruro.app.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cx.miruro.app.data.Subtitle
import cx.miruro.app.data.embed.EmbedResolver
import cx.miruro.app.data.embed.MirrorSource
import cx.miruro.app.data.embed.ResolvedEmbed
import cx.miruro.app.data.embed.ResolvedPlaybackCache
import cx.miruro.app.data.embed.SourceStream
import cx.miruro.app.data.oneshows.MediaDetail
import cx.miruro.app.data.oneshows.MediaEpisode
import cx.miruro.app.data.oneshows.MediaKind
import cx.miruro.app.data.oneshows.OneShowsApi
import cx.miruro.app.data.oneshows.SeasonInfo
import cx.miruro.app.data.subtitles.OpenSubtitles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShowDetailUiState(
    val detail: MediaDetail? = null,
    val selectedSeason: Int? = null,
    val episodes: List<MediaEpisode> = emptyList(),
    val loading: Boolean = true,
    val episodesLoading: Boolean = false,
    val error: String? = null,
    val episodesError: String? = null,
    val resolving: Boolean = false,
    /** The episode whose tap started the resolve, for its row spinner. */
    val resolvingEpisode: Int? = null,
    val resolved: ResolvedEmbed? = null,
    /** "S1 E1" or "Movie": what the player puts under the title, next to the mirror. */
    val resolvedCaption: String? = null,
    val resolvedSeason: Int = 0,
    val resolvedEpisode: Int = 0,
    /** Where the player should start: non-zero when resuming from Continue watching. */
    val resolvedStartMs: Long = 0L,
    /** Mirrors still to try: the player resolves them behind playback. */
    val resolvedAlternates: List<MirrorSource> = emptyList(),
    val resolvedSubtitles: List<Subtitle> = emptyList(),
    val resolveError: String? = null
)

class ShowDetailViewModel(private val api: OneShowsApi, private val kind: MediaKind) : ViewModel() {

    private val resolver = EmbedResolver()

    private val _state = MutableStateFlow(ShowDetailUiState())
    val state: StateFlow<ShowDetailUiState> = _state.asStateFlow()

    private var mediaId = ""
    private var imdbIdCache: String? = null

    /** "Continue watching" may only auto-play once per visit, or Back would re-enter the player. */
    private var resumeHandled = false
    private var detailJob: Job? = null
    private var seasonJob: Job? = null

    fun load(id: String) {
        mediaId = id
        detailJob?.cancel()
        seasonJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }

        detailJob =
            viewModelScope.launch {
                runCatching { api.detail(id, kind) }
                    .onSuccess { detail ->
                        val season = defaultSeason(detail.seasons)
                        _state.update {
                            it.copy(
                                detail = detail,
                                loading = false,
                                error = null,
                                selectedSeason = season?.number,
                                episodes = emptyList(),
                                episodesError = null
                            )
                        }
                        if (detail.kind == MediaKind.TV && season != null) {
                            loadSeason(id, season.number)
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.update {
                            it.copy(
                                loading = false,
                                error = error.message ?: error.javaClass.simpleName
                            )
                        }
                    }
            }
    }

    fun selectSeason(number: Int) {
        if (_state.value.selectedSeason == number) return
        loadSeason(mediaId, number)
    }

    fun retry() {
        load(mediaId)
    }

    fun retryEpisodes() {
        val season = _state.value.selectedSeason ?: return
        loadSeason(mediaId, season)
    }

    private fun loadSeason(id: String, number: Int) {
        seasonJob?.cancel()
        _state.update {
            it.copy(selectedSeason = number, episodesLoading = true, episodesError = null)
        }

        seasonJob =
            viewModelScope.launch {
                runCatching { api.season(id, number) }
                    .onSuccess { episodes ->
                        _state.update {
                            it.copy(
                                episodes = episodes,
                                episodesLoading = false,
                                episodesError = null
                            )
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.update {
                            it.copy(
                                episodesLoading = false,
                                episodesError = error.message ?: error.javaClass.simpleName
                            )
                        }
                    }
            }
    }

    /** Tries each mirror in order until one page hands back a playlist. */
    fun resolvePlayback(
        context: android.content.Context,
        season: Int,
        episode: Int,
        startMs: Long = 0L
    ) {
        if (_state.value.resolving) return

        // Returning from the player, or reopening the page, should not spin up a WebView again.
        val cacheKey = ResolvedPlaybackCache.Key(mediaId, kind.name, season, episode)
        val cached = ResolvedPlaybackCache.get(cacheKey)
        if (cached != null) {
            _state.update {
                it.copy(
                    resolved = cached.sources.firstOrNull()?.stream,
                    resolvedCaption = cached.caption,
                    resolvedSeason = season,
                    resolvedEpisode = episode,
                    resolvedStartMs = startMs,
                    resolvedAlternates = cached.untried,
                    resolvedSubtitles = cached.subtitles,
                    resolveError = null
                )
            }
            return
        }

        _state.update {
            it.copy(
                resolving = true,
                resolvingEpisode = if (kind == MediaKind.TV) episode else null,
                resolveError = null,
                resolved = null
            )
        }

        viewModelScope.launch {
            // Subtitles come from OpenSubtitles, not the mirrors, so fetch them in parallel.
            val subtitles = subtitlesFor(season, episode)

            val providers =
                runCatching { api.embedProviders(mediaId, kind, season, episode) }.getOrNull()
            if (providers.isNullOrEmpty()) {
                _state.update {
                    it.copy(
                        resolving = false,
                        resolvingEpisode = null,
                        resolveError = "No mirrors found"
                    )
                }
                return@launch
            }

            var failure = "No mirror returned a playlist"
            for ((index, provider) in providers.withIndex()) {
                val stream = resolver.resolve(context, provider.url, provider.label)
                if (stream != null) {
                    val caption =
                        if (kind == MediaKind.MOVIE) "Movie" else "S$season E$episode"
                    val untried =
                        providers
                            .drop(index + 1)
                            .map { MirrorSource(label = it.label, url = it.url) }
                    ResolvedPlaybackCache.put(
                        cacheKey,
                        ResolvedPlaybackCache.Hit(
                            sources = listOf(SourceStream(provider.label, stream)),
                            caption = caption,
                            untried = untried,
                            subtitles = subtitles
                        )
                    )
                    _state.update {
                        it.copy(
                            resolving = false,
                            resolvingEpisode = null,
                            resolved = stream,
                            resolvedCaption = caption,
                            resolvedSeason = season,
                            resolvedEpisode = episode,
                            resolvedStartMs = startMs,
                            resolvedAlternates = untried,
                            resolvedSubtitles = subtitles
                        )
                    }
                    return@launch
                }
                failure = "${provider.label} did not return a playlist"
            }
            _state.update {
                it.copy(resolving = false, resolvingEpisode = null, resolveError = failure)
            }
        }
    }

    /** IMDb id first, then every track OpenSubtitles has for this episode. */
    private suspend fun subtitlesFor(season: Int, episode: Int): List<Subtitle> {
        val imdb =
            imdbIdCache ?: api.imdbId(mediaId, kind)?.also { imdbIdCache = it }
                ?: return emptyList()
        val forEpisode = if (kind == MediaKind.MOVIE) null else episode
        val tracks =
            OpenSubtitles.search(
                imdbId = imdb,
                season = if (kind == MediaKind.MOVIE) null else season,
                episode = forEpisode
            )
        return tracks
    }

    fun resumeOnce(context: android.content.Context, target: ResumeTarget) {
        if (resumeHandled) return
        resumeHandled = true
        if (kind == MediaKind.TV) selectSeason(target.season)
        resolvePlayback(context, target.season, target.episode, target.startMs)
    }

    fun consumeResolved() {
        _state.update { it.copy(resolved = null) }
    }
}

/**
 * Season 1 when it has episodes
 *
 * Season 0 holds specials and some entries ship empty placeholder seasons.
 */
internal fun defaultSeason(seasons: List<SeasonInfo>): SeasonInfo? =
    seasons.firstOrNull { it.number == 1 && it.episodeCount > 0 }
        ?: seasons.firstOrNull { it.episodeCount > 0 }
        ?: seasons.firstOrNull()
