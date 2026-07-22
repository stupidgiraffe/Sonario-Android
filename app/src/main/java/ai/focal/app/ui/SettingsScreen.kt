package ai.focal.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.focal.app.data.EngineChoice
import ai.focal.app.llm.LlmProvider
import java.util.Locale

/** Provider-scoped BYOK settings using the app's existing visual language. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SummaryViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    Scaffold(
        containerColor = FocalColors.Deep,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = FocalColors.Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                            tint = FocalColors.InkSoft,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FocalColors.Deep),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(scroll).padding(16.dp),
        ) {
            Text(
                "Where the AI runs",
                color = FocalColors.InkSoft,
                style = MaterialTheme.typography.labelLarge,
            )
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
                subtitle = "Fast. Sends your text only to the provider you select below.",
                selected = ui.engineChoice == EngineChoice.CLOUD,
                onClick = { vm.setEngine(EngineChoice.CLOUD) },
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = FocalColors.RuleSoft)
            Spacer(Modifier.height(20.dp))
            CloudProviderSection(vm, ui)

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = FocalColors.RuleSoft)
            Spacer(Modifier.height(12.dp))
            Text(
                "Focal 1.0.0 • multi-provider BYOK",
                color = FocalColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudProviderSection(vm: SummaryViewModel, ui: UiState) {
    val provider = ui.cloudProvider
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember(provider) { mutableStateOf(false) }
    var keyInput by remember(provider) { mutableStateOf("") }
    var modelInput by remember(provider, ui.providerModel) { mutableStateOf(ui.providerModel) }
    var urlInput by remember(provider, ui.customBaseUrl) { mutableStateOf(ui.customBaseUrl) }

    Text("Cloud provider", color = FocalColors.InkSoft,
        style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        "Credentials and model choices are stored separately for each provider.",
        color = FocalColors.Muted,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))

    ExposedDropdownMenuBox(
        expanded = providerExpanded,
        onExpandedChange = { providerExpanded = !providerExpanded },
    ) {
        OutlinedTextField(
            value = provider.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = focalFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = providerExpanded,
            onDismissRequest = { providerExpanded = false },
        ) {
            LlmProvider.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.displayName) },
                    onClick = {
                        vm.setCloudProvider(choice)
                        providerExpanded = false
                    },
                )
            }
        }
    }

    ui.providerIssue?.let { issue ->
        Spacer(Modifier.height(10.dp))
        Text(issue, color = FocalColors.Teal, style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = FocalColors.RuleSoft)
    Spacer(Modifier.height(16.dp))

    Text(
        "${provider.displayName} API key${if (provider.needsKey) "" else " (optional)"}",
        color = FocalColors.InkSoft,
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (provider.needsKey) {
            "Required by this provider. Stored with Android Keystore-backed encryption."
        } else {
            "Leave blank for an unauthenticated local endpoint, or save a key if your endpoint requires one."
        },
        color = FocalColors.Muted,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))

    if (ui.providerKeySet) {
        Text(
            "Key saved: ${vm.maskedProviderKey()}",
            color = FocalColors.Green,
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = vm::clearProviderKey) {
            Text("Remove key", color = FocalColors.Teal)
        }
    }

    if (provider == LlmProvider.GROQ && vm.hasTrackedDailyBudget()) {
        val (used, limit) = vm.groqDailyUsage()
        val remaining = (limit - used).coerceAtLeast(0)
        val percent = if (limit > 0) (remaining * 100 / limit).toInt() else 0
        Text(
            "App-tracked daily budget: $percent% remaining " +
                "(~${fmtK(remaining)} of ${fmtK(limit)} tokens)",
            color = if (percent < 15) FocalColors.Teal else FocalColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = vm::resetDailyBudget) {
            Text("Reset app counter", color = FocalColors.Green)
        }
    }

    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        placeholder = { Text("Paste key") },
        label = { Text(if (ui.providerKeySet) "Replace API key" else "API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
        colors = focalFieldColors(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            vm.setProviderKey(keyInput.trim())
            keyInput = ""
        },
        enabled = keyInput.isNotBlank(),
        colors = ButtonDefaults.buttonColors(
            containerColor = FocalColors.Green,
            contentColor = FocalColors.Abyss,
        ),
    ) { Text("Save key") }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = FocalColors.RuleSoft)
    Spacer(Modifier.height(16.dp))

    Text("${provider.displayName} model", color = FocalColors.InkSoft,
        style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    ExposedDropdownMenuBox(
        expanded = modelExpanded,
        onExpandedChange = {
            if (provider.suggestedModels.isNotEmpty()) modelExpanded = !modelExpanded
        },
    ) {
        OutlinedTextField(
            value = modelInput,
            onValueChange = {
                modelInput = it
                if (provider.suggestedModels.isNotEmpty()) modelExpanded = true
            },
            label = { Text("Model ID") },
            singleLine = true,
            trailingIcon = if (provider.suggestedModels.isNotEmpty()) {
                { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
            colors = focalFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = modelExpanded,
            onDismissRequest = { modelExpanded = false },
        ) {
            provider.suggestedModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        modelInput = model
                        vm.setProviderModel(model)
                        modelExpanded = false
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { vm.setProviderModel(modelInput) },
        enabled = modelInput.isNotBlank(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = FocalColors.InkSoft),
    ) { Text("Save model") }

    if (provider == LlmProvider.CUSTOM || provider == LlmProvider.OLLAMA) {
        Spacer(Modifier.height(20.dp))
        Text("Base URL", color = FocalColors.InkSoft,
            style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            if (provider == LlmProvider.CUSTOM) {
                "Required. Focal appends the OpenAI-compatible chat endpoint path."
            } else {
                "Optional override. Leave blank to use ${provider.baseUrl}."
            },
            color = FocalColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            placeholder = { Text(provider.baseUrl.ifBlank { "https://example.com/v1" }) },
            label = { Text("Base URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
            colors = focalFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.setCustomBaseUrl(urlInput) },
            enabled = provider != LlmProvider.CUSTOM || urlInput.isNotBlank(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = FocalColors.InkSoft),
        ) { Text("Save base URL") }
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
                    unselectedColor = FocalColors.Muted,
                ),
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

private fun fmtK(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}
