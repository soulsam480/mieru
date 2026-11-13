package cx.miruro.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import cx.miruro.app.data.StreamOption
import cx.miruro.app.data.SubtitleStyle

internal enum class PlayerSheet { None, Sources, Subtitles, SubtitleStyle, Quality }

/** Transient chrome state: visibility, which sheet is open, chosen tracks. */
@Stable
internal class PlayerChromeState {
    var visible by mutableStateOf(true)
    var openSheet by mutableStateOf(PlayerSheet.None)
    var subtitleLanguage by mutableStateOf<String?>(DEFAULT_SUBTITLE_LANGUAGE)
    var qualityLabel by mutableStateOf<String?>(null)
}

@Composable
internal fun PlayerTopBar(
    title: String,
    episode: Int,
    provider: String?,
    isFullscreen: Boolean,
    onBack: () -> Unit,
    onPreviousEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
    onOpenInfo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 6.dp, bottom = 8.dp, start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "Now playing" },
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "EP $episode" + (provider?.let { " · $it" } ?: ""),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }

        PlayerPill("Info", enabled = true, onClick = onOpenInfo)
        PlayerPill("Prev", enabled = onPreviousEpisode != null) { onPreviousEpisode?.invoke() }
        PlayerPill("Next", enabled = onNextEpisode != null) { onNextEpisode?.invoke() }
        PlayerPill(if (isFullscreen) "Exit" else "Full") { onToggleFullscreen() }
    }
}

@Composable
internal fun PlayerBottomBar(
    controller: PlayerController,
    chrome: PlayerChromeState,
    hasSources: Boolean,
    onOpenSheet: (PlayerSheet) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))
            )
            .padding(bottom = 18.dp, start = 10.dp, end = 10.dp)
    ) {
        SeekBar(controller)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.weight(1f))

            if (hasSources) {
                PlayerPill("Source") { onOpenSheet(PlayerSheet.Sources) }
            }
            PlayerPill(
                label = if (chrome.subtitleLanguage == null) "CC off" else "CC",
                muted = chrome.subtitleLanguage == null
            ) { onOpenSheet(PlayerSheet.Subtitles) }
            PlayerPill(chrome.qualityLabel ?: "Auto") { onOpenSheet(PlayerSheet.Quality) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheets(
    controller: PlayerController,
    chrome: PlayerChromeState,
    options: List<StreamOption>,
    selectedSource: Int,
    onSelectSource: (Int) -> Unit,
    style: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit
) {
    val open = chrome.openSheet
    if (open == PlayerSheet.None) return

    val dismiss = { chrome.openSheet = PlayerSheet.None }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        // Always fully expanded: in landscape the partial state leaves too little room to
        // scroll a long source list.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        when (open) {
            PlayerSheet.Sources -> SheetContent("Sources") {
                options.forEachIndexed { index, option ->
                    SheetRow(
                        label = option.label,
                        supporting = when (option.subtitleCount) {
                            0 -> "no subtitles"
                            1 -> "1 subtitle track"
                            else -> "${option.subtitleCount} subtitle tracks"
                        },
                        selected = index == selectedSource,
                        onClick = {
                            onSelectSource(index)
                            dismiss()
                        }
                    )
                }
            }

            PlayerSheet.Subtitles -> SheetContent("Subtitles") {
                SheetRow(
                    label = "Subtitle style",
                    supporting = "${style.size} · ${style.color} · ${style.background} background",
                    selected = false,
                    onClick = { chrome.openSheet = PlayerSheet.SubtitleStyle }
                )
                SheetRow(
                    label = "Off",
                    selected = chrome.subtitleLanguage == null,
                    onClick = {
                        chrome.subtitleLanguage = null
                        controller.useTextGroup(null)
                        dismiss()
                    }
                )
                if (controller.textGroups.isEmpty()) {
                    SheetRow(label = "No subtitles in this source", selected = false) {}
                }
                controller.textGroups.forEach { group ->
                    (0 until group.length).forEach { index ->
                        val format = group.getTrackFormat(index)
                        val label = subtitleLabel(format)
                        SheetRow(
                            label = label,
                            supporting = format.language,
                            selected =
                                chrome.subtitleLanguage.equals(
                                    format.language,
                                    ignoreCase = true
                                ) ||
                                    (chrome.subtitleLanguage == label),
                            onClick = {
                                chrome.subtitleLanguage = format.language ?: label
                                controller.useTextGroup(group)
                                dismiss()
                            }
                        )
                    }
                }
            }

            PlayerSheet.SubtitleStyle -> SheetContent("Subtitle style") {
                SectionLabel("Size")
                listOf("S", "M", "L", "XL").forEach { size ->
                    SheetRow(
                        label = size,
                        selected = style.size == size,
                        onClick = { onStyleChange(style.copy(size = size)) }
                    )
                }
                SectionLabel("Colour")
                listOf("White", "Yellow", "Cyan").forEach { color ->
                    SheetRow(
                        label = color,
                        selected = style.color == color,
                        onClick = { onStyleChange(style.copy(color = color)) }
                    )
                }
                SectionLabel("Background")
                listOf("None", "Dim", "Solid").forEach { background ->
                    SheetRow(
                        label = background,
                        selected = style.background == background,
                        onClick = { onStyleChange(style.copy(background = background)) }
                    )
                }
                SectionLabel("Outline")
                listOf(true to "On", false to "Off").forEach { (value, label) ->
                    SheetRow(
                        label = label,
                        selected = style.outline == value,
                        onClick = { onStyleChange(style.copy(outline = value)) }
                    )
                }
            }

            PlayerSheet.Quality -> SheetContent("Quality") {
                SheetRow(
                    label = "Auto",
                    supporting = "Adapts to your connection",
                    selected = chrome.qualityLabel == null,
                    onClick = {
                        chrome.qualityLabel = null
                        controller.useVideoGroup(null)
                        dismiss()
                    }
                )
                controller.videoGroups.forEach { group ->
                    val format = group.getTrackFormat(0)
                    val label = videoLabel(format)
                    SheetRow(
                        label = label,
                        supporting = format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
                        selected = chrome.qualityLabel == label,
                        onClick = {
                            chrome.qualityLabel = label
                            controller.useVideoGroup(group)
                            dismiss()
                        }
                    )
                }
            }

            PlayerSheet.None -> Unit
        }
    }
}

@Composable
private fun SheetContent(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)
        )
        Column(content = content)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SheetRow(
    label: String,
    selected: Boolean,
    supporting: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Small translucent pill used for every control in the chrome. */
@Composable
private fun PlayerPill(
    label: String,
    enabled: Boolean = true,
    muted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = Color.White.copy(
                alpha = if (muted) {
                    0.55f
                } else if (enabled) {
                    0.95f
                } else {
                    0.3f
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SeekBar(controller: PlayerController) {
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val fraction = when {
        isScrubbing -> scrubFraction
        controller.durationMs > 0 -> controller.positionMs.toFloat() / controller.durationMs
        else -> 0f
    }

    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatPlaybackTime(controller.positionMs),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = {
                isScrubbing = true
                scrubFraction = it
            },
            onValueChangeFinished = {
                controller.seekTo((scrubFraction * controller.durationMs).toLong())
                isScrubbing = false
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            )
        )
        Text(
            formatPlaybackTime(controller.durationMs),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
internal fun PauseGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(2) {
            Box(
                Modifier
                    .size(width = 5.dp, height = 22.dp)
                    .background(Color.White, RoundedCornerShape(1.dp))
            )
        }
    }
}

private fun subtitleLabel(format: Format): String {
    val language = format.language?.uppercase()
    val name = when (format.language?.lowercase()) {
        "eng", "en" -> "English"
        "jpn", "ja" -> "Japanese"
        "spa", "es" -> "Spanish"
        "por", "pt" -> "Portuguese"
        "fre", "fra", "fr" -> "French"
        "ger", "deu", "de" -> "German"
        "ita", "it" -> "Italian"
        "ara", "ar" -> "Arabic"
        "ind", "id" -> "Indonesian"
        "may", "ms" -> "Malay"
        "chi", "zh" -> "Chinese"
        "rus", "ru" -> "Russian"
        "kor", "ko" -> "Korean"
        else -> null
    }
    return listOfNotNull(name, format.label ?: language).firstOrNull() ?: "Track"
}

private fun videoLabel(format: Format): String =
    format.height.takeIf { it > 0 }?.let { "${it}p" } ?: format.label ?: "Track"

private const val DEFAULT_SUBTITLE_LANGUAGE = "en"

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 2.dp)
    )
}
