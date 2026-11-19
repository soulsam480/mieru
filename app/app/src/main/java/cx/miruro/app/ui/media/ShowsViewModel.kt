package cx.miruro.app.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cx.miruro.app.data.oneshows.MediaCard
import cx.miruro.app.data.oneshows.MediaKind
import cx.miruro.app.data.oneshows.OneShowsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShowsUiState(
    val items: List<MediaCard> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null
)

class ShowsViewModel(private val api: OneShowsApi, private val kind: MediaKind) : ViewModel() {

    private val _state = MutableStateFlow(ShowsUiState())
    val state: StateFlow<ShowsUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var loadJob: Job? = null

    init {
        load()
    }

    /** Typing is debounced and any request in flight is cancelled, so a stale page cannot win. */
    fun onQueryChange(value: String) {
        if (value == _state.value.query) return
        _state.update { it.copy(query = value) }

        searchJob?.cancel()
        loadJob?.cancel()

        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MILLIS)
                load()
            }
    }

    fun retry() = load()

    private fun load() {
        val query = _state.value.query.trim()

        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }

        loadJob =
            viewModelScope.launch {
                runCatching { if (query.isEmpty()) api.trending(kind) else api.search(query) }
                    .onSuccess { items ->
                        _state.update { it.copy(items = items, loading = false, error = null) }
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

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 400L
    }
}
