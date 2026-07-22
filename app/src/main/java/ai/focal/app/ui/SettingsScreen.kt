package ai.focal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.focal.app.data.EngineChoice

/**
 * Settings: choose the engine (on-device vs cloud), and for cloud mode
 * paste a Groq API key and set the model string.
 *
 * API keys are stored in hardware-backed encrypted storage (SecureStorage)
 * and never displayed in full — only a masked preview is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SummaryViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    var keyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf(ui.groqModel) }

    Scaffold(
        containerColor = FocalColors.Deep,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = FocalColors.Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                            tint = FocalColors.InkSoft)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = FocalColors.Deep),
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(scroll).padding(16.dp)
        ) {
            // Engine choice
            Text("Where the AI runs", color = FocalColors.InkSoft,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            EngineOption(
                title = "On-device",
                subtitle = "Runs the model on your phone. Private, but slow " +
                        "(CPU only). Needs a downloaded model.",
                selected = ui.engineChoice == EngineChoice.ON_DEVICE,
                onClick = { vm.setEngine(EngineChoice.ON_DEVICE) },
            )
            EngineOption(
                title = "Cloud (BYOK)",
                subtitle = "Fast. Sends your text to the provider of your choice. " +
                        "Needs an API key.",
                selected = ui.engineChoice == EngineChoice.CLOUD,
                onClick = { vm.setEngine(EngineChoice.CLOUD) },
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = FocalColors.RuleSoft)
            Spacer(Modifier.height(20.dp))

            // Cloud / BYOK settings
            Text("Cloud provider", color = FocalColors.InkSoft,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Select a cloud provider. Your API key is stored encrypted " +
                "on this device and sent only when you summarize.",
                color = FocalColors.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))

            // Provider quick-pick row
            // TODO: wire per-provider config UI when multi-provider ViewModel lands

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = FocalColors.RuleSoft)
            Spacer(Modifier.height(20.dp))

            // API key
            Text("API key", color = FocalColors.InkSoft,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste your cloud provider API key. It's stored encrypted " +
                "and only used when you summarize.",
                color = FocalColors.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))

            if (ui.groqKeySet) {
                Text("Key saved: ${vm.currentGroqKeyMasked()}",
                    color = FocalColors.Green,
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val (used, limit) = vm.groqDailyUsage()
                val remaining = (limit - used).coerceAtLeast(0)
                val pct = if (limit > 0) (remaining * 100 / limit).toInt() else 0
                Text(
                    "Daily budget: $pct% remaining " +
                    "(~${fmtK(remaining)} of ${fmtK(limit)} tokens left today)",
                    color = if (pct < 15) FocalColors.Teal else FocalColors.Muted,
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Usage counted by this app only; resets daily.",
                    color = FocalColors.Muted,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { vm.resetDailyBudget() }) {
                    Text("Reset counter", color = FocalColors.Green)
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                placeholder = { Text("gsk_... / sk-...") },
                label = { Text(if (ui.groqKeySet) "Replace API key" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                colors = focalFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (keyInput.isNotBlank()) {
                        vm.setGroqKey(keyInput.trim())
                        keyInput = ""
                    }
                },
                enabled = keyInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FocalColors.Green,
                    contentColor = FocalColors.Abyss),
            ) { Text("Save key") }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                label = { Text("Model") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                colors = focalFieldColors(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Model IDs vary by provider. Focal defaults Groq to Qwen 3.6 27B; " +
                    "use a model ID currently supported by your provider.",
                color = FocalColors.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.setGroqModel(modelInput) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = FocalColors.InkSoft),
            ) { Text("Save model") }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = FocalColors.RuleSoft)
            Spacer(Modifier.height(12.dp))
            Text(
                "Focal 1.4.0 • BYOK multi-provider beta",
                color = FocalColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EngineOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) FocalColors.Panel2 else FocalColors.Panel,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        onClick = onClick,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = FocalColors.Green,
                    unselectedColor = FocalColors.Muted),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, color = FocalColors.Ink, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = FocalColors.Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Compact token count: 480000 -> "480K", 1200000 -> "1.2M". */
private fun fmtK(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1000}K"
    else -> n.toString()
}
