package cx.miruro.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import cx.miruro.app.data.UpdateChecker
import cx.miruro.app.data.UpdateInfo
import kotlinx.coroutines.launch

/**
 * The API host is the one thing that can vanish overnight. Keep it editable, keep a reset,
 * and let people verify it before leaving the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: MiruroApi,
    endpoints: EndpointStore,
    updates: UpdateChecker,
    onBack: () -> Unit
) {
    var draft by remember { mutableStateOf(endpoints.baseUrl()) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tokenDraft by remember { mutableStateOf(updates.token.orEmpty()) }
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
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "API server",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "The app talks to a Miruro-compatible server. If the site moves or goes down, " +
                    "put a working host here — mirrors work as long as they serve the same API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    status = null
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
                            checking = true
                            endpoints.save(draft)
                            draft = endpoints.baseUrl()
                            status = if (api.health()) "Reachable" else "No answer from /health"
                            checking = false
                        }
                    },
                    enabled = !checking
                ) {
                    Text("Save & test")
                }
                TextButton(
                    onClick = {
                        endpoints.reset()
                        draft = endpoints.baseUrl()
                        status = null
                    }
                ) {
                    Text("Reset to default")
                }
                if (checking) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                }
            }

            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Updates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Installed version ${updates.installedVersion}. Not on a store — it updates " +
                    "itself from this project's public GitHub releases. No account needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GitHub token (optional)") },
                placeholder = { Text("only needed for a private repo") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        updates.saveToken(tokenDraft)
                        updateStatus =
                            if (updates.isPrivateRepo) {
                                "Token cleared"
                            } else {
                                "Token saved"
                            }
                    }
                ) {
                    Text("Save token")
                }
                TextButton(
                    onClick = {
                        tokenDraft = ""
                        updates.saveToken(null)
                        updateStatus = "Token cleared"
                    }
                ) {
                    Text("Clear")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            updateStatus = "Checking…"
                            updates.check()
                                .onSuccess { info ->
                                    pendingUpdate = info
                                    updateStatus =
                                        info?.let { "Version ${it.version} is available" }
                                            ?: "You are on the latest version"
                                }
                                .onFailure { updateStatus = "Check failed: ${it.message}" }
                            busy = false
                        }
                    },
                    enabled = !busy
                ) {
                    Text("Check for updates")
                }

                pendingUpdate?.let { info ->
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                updateStatus = "Downloading ${info.version}…"
                                runCatching {
                                    val apk = updates.download(info)
                                    updates.install(apk)
                                    "Downloaded — confirm the install prompt"
                                }.onSuccess { updateStatus = it }
                                    .onFailure { updateStatus = "Download failed: ${it.message}" }
                                busy = false
                            }
                        },
                        enabled = !busy
                    ) {
                        Text("Download & install")
                    }
                }
            }

            updateStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Default: ${EndpointStore.DEFAULT_BASE_URL}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Changing this reloads the catalogue. Watch progress is stored on the device and " +
                    "is not affected.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
