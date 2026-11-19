package cx.miruro.app.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cx.miruro.app.data.embed.EmbedResolver
import cx.miruro.app.data.embed.ResolvedEmbed
import cx.miruro.app.data.oneshows.MediaDetail
import cx.miruro.app.data.oneshows.MediaEpisode
import cx.miruro.app.data.oneshows.MediaKind
import cx.miruro.app.data.oneshows.OneShowsApi
import cx.miruro.app.data.oneshows.SeasonInfo
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
    val resolved: ResolvedEmbed? = null,
    val resolveError: String? = null
)

class ShowDetailViewModel(
    private val api: OneShowsApi,
    private val kind: MediaKind,
    private val resolver: EmbedResolver
) : ViewModel() {

    private val _state = MutableStateFlow(ShowDetailUiState())
    val state: StateFlow<ShowDetailUiState> = _state.asStateFlow()

    private var mediaId = ""
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
    fun resolvePlayback(season: Int, episode: Int) {
        if (_state.value.resolving) return
        _state.update { it.copy(resolving = true, resolveError = null, resolved = null) }

        viewModelScope.launch {
            val providers =
                runCatching { api.embedProviders(mediaId, kind, season, episode) }.getOrNull()
            if (providers.isNullOrEmpty()) {
                _state.update { it.copy(resolving = false, resolveError = "No mirrors found") }
                return@launch
            }

            var failure = "No mirror returned a playlist"
            for (provider in providers) {
                val stream = resolver.resolve(provider.url)
                if (stream != null) {
                    _state.update { it.copy(resolving = false, resolved = stream) }
                    return@launch
                }
                failure = "${provider.label} did not return a playlist"
            }
            _state.update { it.copy(resolving = false, resolveError = failure) }
        }
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
