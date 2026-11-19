package cx.miruro.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cx.miruro.app.data.AutoSkipStore
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.PlaybackPrefsStore
import cx.miruro.app.data.SourceStore
import cx.miruro.app.data.TrackPreference
import cx.miruro.app.data.UpdateChecker
import cx.miruro.app.data.UpdateInfo
import cx.miruro.app.data.oneshows.OneShowsStore
import kotlinx.coroutines.launch

/** Sources, playback preference, and self-updates. Nothing else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: MiruroApi,
    sources: SourceStore,
    updates: UpdateChecker,
    prefs: PlaybackPrefsStore,
    autoSkip: AutoSkipStore,
    oneShowsSource: OneShowsStore,
    onBack: () -> Unit
) {
    var allSources by remember { mutableStateOf(sources.sources()) }
    var selectedId by remember { mutableStateOf(sources.selected().id) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var sourceStatus by remember { mutableStateOf<String?>(null) }
    var serverBusy by remember { mutableStateOf(false) }

    var oneShowsUrl by remember { mutableStateOf(oneShowsSource.site()) }
    var oneShowsStatus by remember { mutableStateOf<String?>(null) }

    var updateStatus by remember { mutableStateOf<String?>(null) }
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateBusy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun refreshSources() {
        allSources = sources.sources()
        selectedId = sources.selected().id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle("Sources")
            Hint(
                "Miruro-compatible servers. One is active — add a mirror and switch when a " +
                    "host moves or blocks."
            )

            allSources.forEach { source ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = source.id == selectedId,
                        onClick = {
                            sources.select(source.id)
                            refreshSources()
                            sourceStatus = null
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            source.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            source.baseUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (allSources.size > 1) {
                        IconButton(
                            onClick = {
                                sources.remove(source.id)
                                refreshSources()
                            }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove source")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            serverBusy = true
                            sourceStatus =
                                if (api.health()) {
                                    "Reachable"
                                } else {
                                    "No answer from /health"
                                }
                            serverBusy = false
                        }
                    },
                    enabled = !serverBusy
                ) {
                    Text("Test active")
                }
                TextButton(
                    onClick = {
                        sources.reset()
                        refreshSources()
                        sourceStatus = null
                    }
                ) {
                    Text("Reset")
                }
                if (serverBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            sourceStatus?.let { Hint(it) }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            SectionTitle("Add a source")
            Hint("Any host that serves the same API works.")
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name (optional)") },
                singleLine = true
            )
            OutlinedTextField(
                value = newUrl,
                onValueChange = { newUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                placeholder = { Text("https://www.miruro.cx") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val added = sources.add(newName, newUrl)
                        if (added != null) {
                            newName = ""
                            newUrl = ""
                            sourceStatus = "Added ${added.name}"
                            refreshSources()
                        } else {
                            sourceStatus = "That URL does not look usable"
                        }
                    },
                    enabled = newUrl.isNotBlank()
                ) {
                    Text("Add")
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle("1shows (TV & Movies)")
            Hint(
                "Metadata and episode playback come from this site. Point it at a mirror when " +
                    "the current host is blocked or goes down; the db. trending host is derived " +
                    "from it."
            )
            OutlinedTextField(
                value = oneShowsUrl,
                onValueChange = {
                    oneShowsUrl = it
                    oneShowsStatus = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Site URL") },
                placeholder = { Text("https://www.1shows.org") },
                singleLine = true
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        oneShowsStatus =
                            if (oneShowsSource.setSite(oneShowsUrl)) {
                                oneShowsUrl = oneShowsSource.site()
                                "Saved. New loads use this site."
                            } else {
                                "That is not a usable URL"
                            }
                    }
                ) { Text("Save") }
                TextButton(onClick = {
                    oneShowsSource.reset()
                    oneShowsUrl = oneShowsSource.site()
                    oneShowsStatus = "Back to the default site"
                }) { Text("Reset") }
            }
            oneShowsStatus?.let { Hint(it) }

            SectionTitle("Playback")
            Hint("Which audio track to pick when a source offers both.")
            var preference by remember { mutableStateOf(prefs.trackPreference()) }
            var autoSkipSettings by remember { mutableStateOf(autoSkip.settings()) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = preference == TrackPreference.SUB,
                    onClick = {
                        preference = TrackPreference.SUB
                        prefs.save(TrackPreference.SUB)
                    },
                    label = { Text("Sub") }
                )
                FilterChip(
                    selected = preference == TrackPreference.DUB,
                    onClick = {
                        preference = TrackPreference.DUB
                        prefs.save(TrackPreference.DUB)
                    },
                    label = { Text("Dub") }
                )
                FilterChip(
                    selected = autoSkipSettings.introAndOutro,
                    onClick = {
                        autoSkipSettings =
                            autoSkipSettings.copy(introAndOutro = !autoSkipSettings.introAndOutro)
                        autoSkip.saveSettings(autoSkipSettings)
                    },
                    label = { Text("Auto-skip intro & outro") }
                )
            }
            Hint("Auto-skip jumps past the opening and ending the provider marked for an episode.")

            Spacer(Modifier.height(14.dp))
            SectionTitle("Updates")
            Hint(
                "Installed ${updates.installedVersion}. Not on a store — it updates itself from " +
                    "this project's GitHub releases."
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            updateBusy = true
                            updateStatus = "Checking…"
                            updates
                                .check()
                                .onSuccess { info ->
                                    pendingUpdate = info
                                    updateStatus =
                                        info?.let { "Version ${it.version} is available" }
                                            ?: "You are on the latest version"
                                }
                                .onFailure { updateStatus = "Check failed: ${it.message}" }
                            updateBusy = false
                        }
                    },
                    enabled = !updateBusy
                ) {
                    Text("Check for updates")
                }

                pendingUpdate?.let { info ->
                    Button(
                        onClick = {
                            scope.launch {
                                updateBusy = true
                                updateStatus = "Downloading ${info.version}…"
                                runCatching {
                                    val apk = updates.download(info)
                                    updates.install(apk)
                                    "Downloaded — confirm the install prompt"
                                }
                                    .onSuccess { updateStatus = it }
                                    .onFailure { updateStatus = "Download failed: ${it.message}" }
                                updateBusy = false
                            }
                        },
                        enabled = !updateBusy
                    ) {
                        Text("Download & install")
                    }
                }
            }

            updateStatus?.let { Hint(it) }

            Spacer(Modifier.height(8.dp))
            Text(
                "Watch progress is stored on this device and is not affected by changing sources.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
