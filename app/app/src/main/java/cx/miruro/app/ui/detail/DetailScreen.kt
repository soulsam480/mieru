package cx.miruro.app.ui.detail

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import cx.miruro.app.data.Anime
import cx.miruro.app.data.Episode
import cx.miruro.app.data.FranchiseStore
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.ProgressStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    api: MiruroApi,
    progress: ProgressStore,
    franchises: FranchiseStore,
    animeId: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onPlay: (animeId: String, episode: Int, title: String) -> Unit
) {
    val vm: DetailViewModel =
        viewModel { DetailViewModel(api, progress, franchises) }

    // Switching season reloads this same screen with a different id.
    var shownId by remember(animeId) { mutableStateOf(animeId) }
    LaunchedEffect(shownId) { vm.load(shownId) }
    val state by vm.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshProgress() }

    val anime = state.anime
    val title = anime?.title?.best() ?: fallbackTitle

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                anime == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not load this show")
                        TextButton(onClick = vm::retry) { Text("Retry") }
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 40.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item { Header(anime) }

                    if (state.seasons.size > 1) {
                        item {
                            SeasonRow(
                                seasons = state.seasons,
                                currentId = shownId,
                                onSelectSeason = { seasonId -> shownId = seasonId }
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = { onPlay(shownId, state.nextEpisode(), title) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            val ep = state.nextEpisode()
                            Text(
                                if (state.watched.isEmpty()) {
                                    "Watch EP $ep"
                                } else {
                                    "Continue · EP $ep"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    item {
                        Text(
                            "Episodes · ${state.episodes.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
                        )
                    }

                    if (state.episodes.isEmpty()) {
                        item {
                            Text(
                                "No episodes available for this entry.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }

                    items(state.episodes, key = { it.episodeNumber }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            fraction = state.watched[episode.number]?.fraction,
                            released = episode.isReleased(),
                            onClick = {
                                // Use the season actually on screen, not the route we arrived by.
                                onPlay(shownId, episode.number, "$title · EP ${episode.number}")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(anime: Anime) {
    val image = anime.bannerUrl ?: anime.backgroundUrl ?: anime.coverUrl

    Column {
        Box {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            AsyncImage(
                model = anime.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    anime.title.best(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val chips = buildList {
                        anime.format?.let { add(it.replace('_', ' ')) }
                        anime.status?.let { add(it.replace('_', ' ')) }
                        anime.seasonYear?.let { add(it.toString()) }
                        anime.averageScore?.takeIf { it > 0 }?.let { add("★ $it") }
                        anime.shownEpisodes?.let { add("$it episodes") }
                    }
                    items(chips.size) { index ->
                        AssistChip(onClick = {}, label = { Text(chips[index]) })
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        anime.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                description.replace(Regex("<[^>]+>"), "").trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
