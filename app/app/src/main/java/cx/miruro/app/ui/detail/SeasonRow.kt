package cx.miruro.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cx.miruro.app.data.Anime

@Composable
internal fun SeasonRow(seasons: List<Anime>, currentId: String, onSelectSeason: (String) -> Unit) {
    val listState = rememberLazyListState()
    val selectedIndex = seasons.indexOfFirst { it.id == currentId }

    // The row starts at season 1, so the active chip is often off-screen. Bring it into view.
    LaunchedEffect(currentId, seasons.size) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(seasons, key = { it.id }) { season ->
            val isCurrent = season.id == currentId
            FilterChip(
                selected = isCurrent,
                onClick = {
                    if (!isCurrent) onSelectSeason(season.id)
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                leadingIcon =
                    if (isCurrent) {
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
                        seasonLabel(season, seasons),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
            )
        }
    }
}

/** "Attack on Titan Final Season Part 2" in a row of AoT entries becomes "Final Season Part 2". */
internal fun seasonLabel(season: Anime, all: List<Anime>): String {
    season.airedSeason?.name?.let { return it }

    val titles = all.map { it.title.best() }
    val shared = titles.reduce { a, b -> a.commonPrefixWith(b) }
    val trimmed = season.title.best().removePrefix(shared).trim(' ', '-', ':')

    return trimmed.ifBlank { season.title.best() }
}
