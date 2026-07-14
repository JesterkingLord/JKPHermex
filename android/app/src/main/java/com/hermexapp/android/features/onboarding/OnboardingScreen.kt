package com.hermexapp.android.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import com.hermexapp.android.ui.HermexWordmark
import com.hermexapp.android.ui.theme.LocalHermexPalette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Welcome → reachability guidance → connect, mirroring the iOS onboarding
 * pages. Connection troubleshooting states render inline on the connect page.
 *
 * v0.3.0+ adds the "Scan QR or paste pairing URL" affordance on the connect
 * page, which posts to `/v1/pair/complete` instead of needing a typed URL +
 * password. The flow is opt-in: the typed URL path still works.
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = com.hermexapp.android.ui.theme.LocalHermexPalette.current.canvas,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.step) {
                OnboardingViewModel.Step.WELCOME -> WelcomePage(onContinue = viewModel::advanceToGuidance)
                OnboardingViewModel.Step.GUIDANCE -> GuidancePage(
                    onContinue = viewModel::advanceToConnect,
                    onBack = viewModel::backToWelcome,
                )
                OnboardingViewModel.Step.CONNECT -> ConnectPage(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun WelcomePage(onContinue: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    // Gold radial-glow hero behind the wordmark — iOS onboarding parity, matching
    // 9thLevel's port: a soft brand-gold bloom fading to transparent.
    val gold = LocalHermexPalette.current.accent
    Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            gold.copy(alpha = 0.45f),
                            gold.copy(alpha = 0.18f),
                            gold.copy(alpha = 0f),
                        ),
                    ),
                    CircleShape,
                ),
        )
        HermexWordmark()
    }
    Text(
        "Control your self-hosted Hermes agent from your phone. " +
            "Your server. Your phone. No middleman.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "You bring your own hermes-webui server running on a machine you control. " +
            "The phone is the control plane — the agent, its tools, and your data stay on your hardware.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

@Composable
private fun GuidancePage(onContinue: () -> Unit, onBack: () -> Unit) {
    Text("Reach your server", style = MaterialTheme.typography.headlineMedium)
    Text(
        "JKP Mobile talks directly to your JKP server, so it must be reachable from this phone:",
        style = MaterialTheme.typography.bodyMedium,
    )
    GuidanceItem(
        title = "HTTPS via a tunnel or reverse proxy (recommended)",
        detail = "Expose the server through Cloudflare Tunnel or any reverse proxy that " +
            "terminates real TLS at a hostname you own, e.g. https://hermes.yourdomain.com. " +
            "Set a strong password — on a public hostname it is your only app-level defense.",
    )
    GuidanceItem(
        title = "Tailscale",
        detail = "Install Tailscale on the server and this phone, run the server bound to all " +
            "interfaces with a password, and connect to http://<tailnet-ip>:8787. " +
            "Plain http is allowed only for Tailscale's 100.64.x.x device range.",
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    TextButton(onClick = onBack) { Text("Back") }
}

@Composable
private fun GuidanceItem(title: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectPage(state: OnboardingViewModel.UiState, viewModel: OnboardingViewModel) {
    var showPairDialog by remember { mutableStateOf(false) }

    Text("Connect", style = MaterialTheme.typography.headlineMedium)

    OutlinedTextField(
        value = state.serverUrlString,
        onValueChange = viewModel::updateServerUrl,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Server URL") },
        placeholder = { Text("https://hermes.yourdomain.com") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        enabled = !state.isWorking,
    )

    if (state.isPasswordRequired) {
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = !state.isWorking,
        )
    }

    if (state.isWorking) {
        CircularProgressIndicator()
    }

    state.connectionMessage?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }

    state.errorMessage?.let { message ->
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TroubleshootingHints()
    }

    OutlinedButton(
        onClick = viewModel::testConnection,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isWorking && state.serverUrlString.isNotBlank(),
    ) { Text("Test connection") }

    Button(
        onClick = viewModel::connect,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isWorking && state.serverUrlString.isNotBlank(),
    ) { Text("Connect") }

    TextButton(
        onClick = { showPairDialog = true },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isWorking,
    ) { Text("Scan QR or paste pairing URL") }

    TextButton(onClick = viewModel::backToGuidance) { Text("Back") }

    if (showPairDialog) {
        PairUrlDialog(
            onDismiss = { showPairDialog = false },
            onSubmit = { raw ->
                showPairDialog = false
                viewModel.pairFromText(raw)
            },
        )
    }
}

@Composable
private fun PairUrlDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair from URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "On the host machine run `python -m jkp pair`, then copy the URL it prints " +
                        "and paste it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pairing URL") },
                    placeholder = { Text("http://100.x.y.z:8642/v1/pair/connect?pair_id=…&token=…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        val clip = clipboard.getText()?.text.orEmpty()
                        if (clip.isNotBlank()) text = clip
                    },
                ) { Text("Paste from clipboard") }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank(),
            ) { Text("Pair") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun TroubleshootingHints() {
    // The README's connection-troubleshooting checklist, verbatim in spirit.
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Check these first:", style = MaterialTheme.typography.titleSmall)
        Text("1. The machine hosting hermes-webui is awake.", style = MaterialTheme.typography.bodySmall)
        Text(
            "2. hermes-webui is running and serving /health (curl https://<your-server>/health).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "3. The tunnel, reverse proxy, or Tailscale route is connected.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("4. The server URL and password are correct.", style = MaterialTheme.typography.bodySmall)
    }
}