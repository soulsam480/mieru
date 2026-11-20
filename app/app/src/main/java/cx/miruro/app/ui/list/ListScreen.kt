package cx.miruro.app.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import cx.miruro.app.data.AiringEntry
import cx.miruro.app.data.Anime
import cx.miruro.app.data.ContinueItem
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.ProgressStore
import cx.miruro.app.data.UpdateChecker
import cx.miruro.app.ui.components.ContinueWatchingRow
import cx.miruro.app.ui.components.MiruroIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    api: MiruroApi,
    progress: ProgressStore,
    onOpenAnime: (Anime) -> Unit,
    onResume: (ContinueItem) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    updates: UpdateChecker
) {
    val vm: ListViewModel = viewModel { ListViewModel(api, progress) }
    val state by vm.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    val availableUpdate by updates.available.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshProgress()
        vm.refreshIfEndpointChanged()
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last =
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    ?: return@derivedStateOf false
            last >= state.items.size - 4
        }
    }
    // Saved, so returning from a title does not look like a key change and jump the grid to top.
    var feedKeys by rememberSaveable {
        mutableStateOf(listOf(state.sort, state.genre, state.query))
    }
    LaunchedEffect(state.sort, state.genre, state.query) {
        val keys = listOf(state.sort, state.genre, state.query)
        if (keys != feedKeys) {
            feedKeys = keys
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(shouldLoadMore, state.error) {
        // Re-keyed on the error so a failed page retries instead of waiting for the next scroll.
        if (shouldLoadMore) vm.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "MIERU",
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            ".app",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenLibrary) {
                        Icon(MiruroIcons.Bookmark, contentDescription = "Library")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search anime") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp)
            )

            TabRow(
                selectedTabIndex = SORTS.indexOfFirst { it.first == state.sort }.coerceAtLeast(0),
                containerColor = MaterialTheme.colorScheme.background
            ) {
                SORTS.forEach { (value, label) ->
                    Tab(
                        selected = state.sort == value,
                        onClick = { vm.onSortChange(value) },
                        text = { Text(label, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }

            availableUpdate?.let { update ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSettings)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Version ${update.version} is available",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Install",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (state.loading && state.items.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            when {
                state.loading && state.items.isEmpty() -> Centered {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                state.error != null && state.items.isEmpty() -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Could not load",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = vm::retry) { Text("Retry") }
                    }
                }

                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 32.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (state.loading) 0.45f else 1f)
                ) {
                    // The Latest feed is what aired this week; genre filters only make sense
                    // for the catalogue tabs.
                    if (state.sort != SORT_LATEST) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            GenreRow(selected = state.genre, onSelect = vm::onGenreChange)
                        }
                    }

                    state.error?.takeIf { state.items.isNotEmpty() }?.let { message ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = vm::retry)
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("Retry", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    if (state.continueItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueWatchingRow(state.continueItems, onResume)
                        }
                    }

                    items(state.items, key = { it.id }) { anime ->
                        AnimeCard(
                            anime = anime,
                            airing = state.airings[anime.id],
                            onClick = { onOpenAnime(anime) }
                        )
                    }

                    if (state.items.isEmpty() && !state.loading && state.continueItems.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Nothing found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (state.loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreRow(selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") }
            )
        }
        items(GENRES.size) { index ->
            val genre = GENRES[index]
            FilterChip(
                selected = selected == genre,
                onClick = { onSelect(if (selected == genre) null else genre) },
                label = { Text(genre) }
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
