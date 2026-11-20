package cx.miruro.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import cx.miruro.app.data.ProgressStore
import cx.miruro.app.data.WatchlistItem
import cx.miruro.app.data.WatchlistStore
import cx.miruro.app.ui.components.ContinueWatchingRow
import cx.miruro.app.ui.components.MiruroIcons

private const val ANIME = "ANIME"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    progress: ProgressStore,
    watchlist: WatchlistStore,
    onBack: () -> Unit,
    onOpenAnime: (id: String, title: String) -> Unit,
    onOpenMedia: (kind: String, id: String, title: String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val progressRevision by progress.revision.collectAsStateWithLifecycle()
    val watchlistRevision by watchlist.revision.collectAsStateWithLifecycle()
    val continueItems = remember(progressRevision) { progress.continueWatchingAll() }
    val saved = remember(watchlistRevision) { watchlist.items() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Library",
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
        if (continueItems.isEmpty() && saved.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here yet. Save something with the bookmark, or start watching.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 32.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                if (continueItems.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ContinueWatchingRow(continueItems) { item ->
                            open(item.kind, item.animeId, item.title, onOpenAnime, onOpenMedia)
                        }
                    }
                }

                if (saved.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Watchlist",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    items(saved, key = { "${it.kind}-${it.id}" }) { entry ->
                        WatchlistCard(
                            item = entry,
                            onClick = {
                                open(entry.kind, entry.id, entry.title, onOpenAnime, onOpenMedia)
                            },
                            onRemove = { watchlist.toggle(entry) }
                        )
                    }
                }
            }
        }
    }
}

private fun open(
    kind: String,
    id: String,
    title: String,
    onOpenAnime: (String, String) -> Unit,
    onOpenMedia: (String, String, String) -> Unit
) {
    if (kind == ANIME) onOpenAnime(id, title) else onOpenMedia(kind, id, title)
}

@Composable
private fun WatchlistCard(item: WatchlistItem, onClick: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                )
            }
            FilledIconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.55f),
                        contentColor = Color.White
                    )
            ) {
                Icon(
                    MiruroIcons.Bookmark,
                    contentDescription = "Remove from watchlist",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight
        )
    }
}
