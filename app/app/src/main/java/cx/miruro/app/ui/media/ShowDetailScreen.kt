package cx.miruro.app.ui.media

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import cx.miruro.app.data.embed.EmbedResolver
import cx.miruro.app.data.embed.PlaybackRequest
import cx.miruro.app.data.oneshows.MediaDetail
import cx.miruro.app.data.oneshows.MediaEpisode
import cx.miruro.app.data.oneshows.MediaKind
import cx.miruro.app.data.oneshows.OneShowsApi
import cx.miruro.app.data.oneshows.SeasonInfo

/** Where "Continue watching" wants playback to resume. */
data class ResumeTarget(val season: Int, val episode: Int, val startMs: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowDetailScreen(
    api: OneShowsApi,
    mediaId: String,
    kind: MediaKind,
    fallbackTitle: String,
    onBack: () -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    resume: ResumeTarget? = null
) {
    val vm: ShowDetailViewModel =
        viewModel(key = "media-detail-${kind.name}-$mediaId") {
            ShowDetailViewModel(api, kind)
        }

    LaunchedEffect(mediaId) { vm.load(mediaId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val detail = state.detail

    val resolved = state.resolved
    LaunchedEffect(resolved) {
        if (resolved != null) {
            onPlay(
                PlaybackRequest(
                    stream = resolved,
                    title = detail?.title ?: fallbackTitle,
                    caption = state.resolvedCaption.orEmpty(),
                    mediaId = mediaId,
                    kind = kind.name,
                    poster = detail?.posterUrl,
                    season = state.resolvedSeason,
                    episode = state.resolvedEpisode,
                    startMs = state.resolvedStartMs,
                    alternates = state.resolvedAlternates,
                    subtitles = state.resolvedSubtitles
                )
            )
            vm.consumeResolved()
        }
    }

    // Tapped from Continue watching: land on the right season and pick up where it stopped. The
    // ViewModel makes this one-shot so returning from the player does not immediately replay.
    LaunchedEffect(detail?.id, resume) {
        if (detail == null || resume == null) return@LaunchedEffect
        vm.resumeOnce(context, resume)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.title ?: fallbackTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
                state.loading && detail == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                detail == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not load this title")
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

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 40.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item { Header(detail) }

                    if (detail.overview.isNotEmpty()) {
                        item {
                            Text(
                                detail.overview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    if (detail.kind == MediaKind.MOVIE) {
                        item {
                            PlaybackNotice(
                                resolving = state.resolving,
                                error = state.resolveError,
                                onResolve = {
                                    // A film has no season; 0 keeps "Continue watching" honest.
                                    vm.resolvePlayback(context, season = 0, episode = 1)
                                },
                                label = "Play movie"
                            )
                        }
                    }

                    if (detail.kind == MediaKind.TV) {
                        if (detail.seasons.isNotEmpty()) {
                            item {
                                SeasonChips(
                                    seasons = detail.seasons,
                                    selected = state.selectedSeason,
                                    onSelect = vm::selectSeason
                                )
                            }
                        }

                        state.resolveError?.let { failure ->
                            item {
                                Text(
                                    failure,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }

                        item {
                            Text(
                                "Episodes · ${state.episodes.size}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    top = 16.dp,
                                    bottom = 6.dp
                                )
                            )
                        }

                        when {
                            state.episodesError != null -> item {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(
                                        "Could not load episodes",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(onClick = vm::retryEpisodes) { Text("Retry") }
                                }
                            }

                            state.episodesLoading -> item {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        Modifier.size(22.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }

                            state.episodes.isEmpty() -> item {
                                Text(
                                    "No episodes listed for this season.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 12.dp
                                    )
                                )
                            }

                            else -> items(state.episodes, key = { it.number }) { episode ->
                                EpisodeRow(
                                    episode = episode,
                                    enabled = !state.resolving,
                                    loading = state.resolvingEpisode == episode.number,
                                    onPlay = {
                                        vm.resolvePlayback(
                                            context,
                                            state.selectedSeason ?: 1,
                                            episode.number
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(detail: MediaDetail) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = detail.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
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
                model = detail.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    detail.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val chips = buildList {
                        detail.year?.let { add(it.toString()) }
                        scoreText(detail.score)?.let { add(it) }
                        runtimeText(detail.runtimeMinutes)?.let { add(it) }
                        val seasons = detail.seasons.count { it.number > 0 }
                        if (detail.kind == MediaKind.TV && seasons > 0) add("$seasons seasons")
                        addAll(detail.genres)
                    }
                    items(chips.size) { index ->
                        AssistChip(onClick = {}, label = { Text(chips[index]) })
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Resolves the episode through the provider mirrors, which run their own JavaScript in an
 * offscreen WebView. The label names the failure so a dead mirror is visible.
 */
@Composable
private fun PlaybackNotice(
    resolving: Boolean,
    error: String?,
    onResolve: () -> Unit,
    label: String = "Play"
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        AssistChip(
            onClick = { if (!resolving) onResolve() },
            leadingIcon = {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            label = { Text(if (resolving) "Resolving…" else label) }
        )
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SeasonChips(seasons: List<SeasonInfo>, selected: Int?, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(seasons, key = { it.number }) { season ->
            val isSelected = season.number == selected
            FilterChip(
                selected = isSelected,
                onClick = { if (!isSelected) onSelect(season.number) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                ),
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    null
                },
                label = {
                    Text(
                        season.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: MediaEpisode,
    enabled: Boolean,
    loading: Boolean,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    episode.number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                episode.title.ifBlank { "Episode ${episode.number}" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub =
                if (loading) {
                    "Resolving…"
                } else {
                    listOfNotNull(episode.airDate, runtimeText(episode.runtimeMinutes))
                        .joinToString(" · ")
                }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
