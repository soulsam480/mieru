package cx.miruro.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cx.miruro.app.data.Anime
import cx.miruro.app.data.Episode
import cx.miruro.app.data.EpisodeProgress
import cx.miruro.app.data.FranchiseStore
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.ProgressStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val animeId: String = "",
    val anime: Anime? = null,
    val episodes: List<Episode> = emptyList(),
    val watched: Map<Int, EpisodeProgress> = emptyMap(),
    val seasons: List<Anime> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
) {
    val releasedEpisodes: List<Episode> get() = episodes.filter { it.isReleased() }

    /** First released episode that is unwatched or unfinished; falls back to the last one. */
    fun nextEpisode(): Int {
        val available = releasedEpisodes
        if (available.isEmpty()) return episodes.firstOrNull()?.number ?: 1

        val candidate =
            available.firstOrNull { episode ->
                val progress = watched[episode.number]
                progress == null || progress.fraction < 0.9f
            }
        return candidate?.number ?: available.last().number
    }
}

class DetailViewModel(
    private val api: MiruroApi,
    private val progress: ProgressStore,
    private val franchises: FranchiseStore
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /**
     * Loads (or switches to) a show. Season switching goes through here too: replacing the state
     * re-renders the whole page — title, artwork, episodes and the selected chip.
     */
    fun load(animeId: String) {
        _state.update { it.copy(animeId = animeId, loading = true, error = null) }

        viewModelScope.launch {
            val show = runCatching { api.show(animeId) }
            val episodes = runCatching { api.episodes(animeId) }

            val anime =
                show.getOrNull()?.data?.firstOrNull { it.id == animeId }
                    ?: show.getOrNull()?.data?.firstOrNull()
            val list = episodes.getOrNull()?.data?.sortedBy { it.episodeNumber } ?: emptyList()
            val watched = watchedFor(animeId, list)

            val failure = show.exceptionOrNull() ?: episodes.exceptionOrNull()
            val seasons = loadSeasons(animeId, show.getOrNull(), anime)

            android.util.Log.d(
                "MiruroSeasons",
                "loaded id=${animeId.take(
                    6
                )} title=${anime?.title?.best()} episodes=${list.size} " +
                    "seasons=${seasons.size} selected=${seasons.indexOfFirst { it.id == animeId }}"
            )

            _state.update {
                it.copy(
                    animeId = animeId,
                    anime = anime,
                    episodes = list,
                    watched = watched,
                    seasons = seasons,
                    loading = false,
                    error = if (anime == null) (failure?.message ?: "Not found") else null
                )
            }
        }
    }

    private suspend fun loadSeasons(
        animeId: String,
        show: cx.miruro.app.data.ShowEnvelope?,
        current: Anime?
    ): List<Anime> {
        // A season we have already visited knows the whole franchise, even when this entry's own
        // order_release is a different (smaller) list.
        val known = franchises.seasonsOf(animeId)
        val releaseOrder = known ?: show?.orderRelease.orEmpty()
        if (releaseOrder.size < 2) return emptyList()

        franchises.register(animeId, releaseOrder)

        val entries = runCatching { api.animeByIds(releaseOrder) }.getOrDefault(emptyList())
        val seasons =
            entries
                .filter(::isTelevisionSeason)
                .distinctBy { it.id }
                .toMutableList()

        if (current != null && seasons.none { it.id == current.id }) {
            seasons += current
        }

        return seasons.sortedWith(
            compareBy(
                { it.airedSeason?.number ?: Int.MAX_VALUE },
                { it.clusterPosition ?: Int.MAX_VALUE },
                { it.seasonYear ?: 0 }
            )
        )
    }

    fun refreshProgress() {
        val state = _state.value
        _state.update { it.copy(watched = watchedFor(state.animeId, state.episodes)) }
    }

    fun retry() {
        load(_state.value.animeId)
    }

    private fun watchedFor(animeId: String, episodes: List<Episode>): Map<Int, EpisodeProgress> =
        episodes
            .mapNotNull { episode ->
                progress.progressOf(animeId, episode.number)?.let { episode.number to it }
            }
            .toMap()
}

/**
 * Only real television seasons. The franchise list also carries movies, OVAs and one-episode
 * specials — those are not "seasons" and several have no episodes to play at all.
 */
private fun isTelevisionSeason(anime: Anime): Boolean =
    anime.format in TV_FORMATS && (anime.shownEpisodes ?: 0) >= 2

private val TV_FORMATS = setOf("TV", "TV_SHORT")
