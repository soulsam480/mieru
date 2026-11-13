package cx.miruro.app.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cx.miruro.app.data.AiringEntry
import cx.miruro.app.data.Anime
import cx.miruro.app.data.CatalogPage
import cx.miruro.app.data.ContinueItem
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.ProgressStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** "latest" is a distinct feed (recently aired episodes); the rest are catalogue sorts. */
const val SORT_LATEST = "latest"

val SORTS =
    listOf(
        SORT_LATEST to "Latest",
        "-popularity" to "Popular",
        "-score" to "Top rated",
        "title" to "A–Z"
    )

val GENRES =
    listOf(
        "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror",
        "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance",
        "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"
    )

data class ListUiState(
    val items: List<Anime> = emptyList(),
    val query: String = "",
    val genre: String? = null,
    val sort: String = SORT_LATEST,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val continueItems: List<ContinueItem> = emptyList(),
    /** animeId -> the episode that just aired (only populated by the "Latest" feed). */
    val airings: Map<String, AiringEntry> = emptyMap()
)

class ListViewModel(private val api: MiruroApi, private val progress: ProgressStore) : ViewModel() {

    private val _state = MutableStateFlow(ListUiState(continueItems = progress.continueWatching()))
    val state: StateFlow<ListUiState> = _state.asStateFlow()

    private var cursor: String? = null
    private var endpointRevision = api.revision()
    private var searchJob: Job? = null

    init {
        load(reset = true)
    }

    /** Called when returning from the player, so "Continue watching" is fresh. */
    fun refreshProgress() {
        _state.update { it.copy(continueItems = progress.continueWatching()) }
    }

    /** Called when returning from Settings: a different server means different data. */
    fun refreshIfEndpointChanged() {
        val revision = api.revision()
        if (revision != endpointRevision) {
            endpointRevision = revision
            load(reset = true)
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MILLIS)
                load(reset = true)
            }
    }

    fun onGenreChange(genre: String?) {
        if (_state.value.genre == genre) return
        _state.update { it.copy(genre = genre) }
        load(reset = true)
    }

    fun onSortChange(sort: String) {
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort) }
        load(reset = true)
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        load(reset = false)
    }

    fun retry() = load(reset = true)

    private fun load(reset: Boolean) {
        val snapshot = _state.value
        if (reset) cursor = null

        _state.update { it.copy(loading = reset, loadingMore = !reset, error = null) }

        viewModelScope.launch {
            runCatching { feedFor(snapshot, reset) }
                .onSuccess { feed ->
                    cursor = feed.page.nextCursor
                    _state.update {
                        it.copy(
                            items = if (reset) feed.page.data else it.items + feed.page.data,
                            airings = feed.airings,
                            hasMore = feed.page.hasMore && feed.page.nextCursor != null,
                            loading = false,
                            loadingMore = false,
                            error = null
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            loadingMore = false,
                            error = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
        }
    }

    private suspend fun feedFor(snapshot: ListUiState, reset: Boolean): Feed =
        if (usesLatestFeed(snapshot)) {
            latestFeed()
        } else {
            Feed(
                api.catalog(
                    limit = PAGE_SIZE,
                    sort = if (snapshot.sort == SORT_LATEST) FALLBACK_SORT else snapshot.sort,
                    q = snapshot.query.takeIf { it.isNotBlank() },
                    genres = listOfNotNull(snapshot.genre),
                    cursor = if (reset) null else cursor
                )
            )
        }

    /**
     * "Latest" is only the default browsing feed. Searching or filtering a genre has to hit the
     * whole catalogue — otherwise searching for a finished show inside "Latest" returns nothing.
     */
    private fun usesLatestFeed(snapshot: ListUiState): Boolean =
        snapshot.sort == SORT_LATEST && snapshot.genre == null && snapshot.query.isBlank()

    /**
     * What aired recently, not a catalogue sort. Titles come from the catalogue by id, and the
     * airing itself is kept so each card can say which episode is new.
     */
    private suspend fun latestFeed(): Feed {
        val airings = api.recentlyAired()
        val airingById = airings.associateBy { it.animeId }

        val newest = airings.take(40)
        val animeById = api.animeByIds(newest.map { it.animeId }).associateBy { it.id }
        val ordered = newest.mapNotNull { animeById[it.animeId] }

        return Feed(
            page = CatalogPage(ordered, null, false),
            airings = airingById
        )
    }

    private data class Feed(
        val page: CatalogPage,
        val airings: Map<String, AiringEntry> = emptyMap()
    )

    private companion object {
        const val PAGE_SIZE = 15
        const val FALLBACK_SORT = "-popularity"
        const val SEARCH_DEBOUNCE_MILLIS = 350L
    }
}
