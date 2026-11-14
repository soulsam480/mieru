package cx.miruro.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cx.miruro.app.data.EndpointStore
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.PlaybackPrefsStore
import cx.miruro.app.data.TrackPreference
import cx.miruro.app.data.UpdateChecker
import cx.miruro.app.data.UpdateInfo
import kotlinx.coroutines.launch

/** Two things only: which server to talk to, and how the app updates itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: MiruroApi,
    endpoints: EndpointStore,
    updates: UpdateChecker,
    prefs: PlaybackPrefsStore,
    onBack: () -> Unit
) {
    var serverDraft by remember { mutableStateOf(endpoints.baseUrl()) }
    var serverStatus by remember { mutableStateOf<String?>(null) }
    var serverBusy by remember { mutableStateOf(false) }

    var updateStatus by remember { mutableStateOf<String?>(null) }
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateBusy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

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
            SectionTitle("API server")
            Hint(
                "Miruro-compatible server. If it moves or goes down, put a working host here — " +
                    "mirrors work as long as they serve the same API."
            )

            OutlinedTextField(
                value = serverDraft,
                onValueChange = {
                    serverDraft = it
                    serverStatus = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                placeholder = { Text(EndpointStore.DEFAULT_BASE_URL) },
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            serverBusy = true
                            endpoints.save(serverDraft)
                            serverDraft = endpoints.baseUrl()
                            serverStatus =
                                if (api.health()) "Reachable" else "No answer from /health"
                            serverBusy = false
                        }
                    },
                    enabled = !serverBusy
                ) {
                    Text("Save & test")
                }
                TextButton(
                    onClick = {
                        endpoints.reset()
                        serverDraft = endpoints.baseUrl()
                        serverStatus = null
                    }
                ) {
                    Text("Reset to default")
                }
                if (serverBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            serverStatus?.let { Hint(it) }

            Spacer(Modifier.height(14.dp))
            SectionTitle("Playback")
            Hint("Which audio track to pick when a source offers both.")
            var preference by remember { mutableStateOf(prefs.trackPreference()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }

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
                "Default server: ${EndpointStore.DEFAULT_BASE_URL}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Watch progress is stored on this device and is not affected by changing servers.",
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
