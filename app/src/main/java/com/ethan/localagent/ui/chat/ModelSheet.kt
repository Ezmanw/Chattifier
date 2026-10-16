package com.ethan.localagent.ui.chat

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ethan.localagent.app
import com.ethan.localagent.data.ComputeBackend
import com.ethan.localagent.data.ModelEntry
import com.ethan.localagent.data.Settings
import com.ethan.localagent.engine.EngineState
import kotlinx.coroutines.launch

@Composable
fun ModelSheet(
    settings: Settings,
    models: List<ModelEntry>,
    engine: EngineState,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = models.firstOrNull { it.id == settings.activeModelId } ?: models.firstOrNull()
    fun update(f: (Settings) -> Settings) = scope.launch { ctx.app.prefs.update(f) }
    fun preload() = scope.launch { runCatching { ctx.app.engine.ensureLoaded() } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            models.forEach { m ->
                ListItem(
                    headlineContent = { Text(m.name) },
                    supportingContent = {
                        val caps = listOfNotNull("Text".takeIf { m.text && !m.audioOnly }, "Vision".takeIf { m.vision }, "Audio".takeIf { m.audio })
                        Text("${caps.joinToString(" · ")} · ${android.text.format.Formatter.formatShortFileSize(ctx, m.sizeBytes)}")
                    },
                    leadingContent = { RadioButton(selected = m.id == active?.id, onClick = null) },
                    modifier = Modifier.clickable { update { it.copy(activeModelId = m.id) }; preload() },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            OutlinedButton(onClick = onManage, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { Text("Get more models") }

            if (active != null) {
                Text("Run on", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp))
                BackendSwitcher(
                    selected = settings.backend,
                    model = active,
                    onSelect = { b -> update { it.copy(backend = b) }; preload() },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    backendBlurb(active.effectiveBackend(settings.backend), active, engine),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            Text("Creativity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 16.dp))
            var temp by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Slider(value = temp, onValueChange = { temp = it }, onValueChangeFinished = { update { s -> s.copy(temperature = temp) } },
                    valueRange = 0f..1.5f, modifier = Modifier.weight(1f))
                Text("%.1f".format(temp), modifier = Modifier.padding(start = 12.dp), fontWeight = FontWeight.Medium)
            }

            Text("Memory (context)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2048, 4096, 8192, 16384).forEach { n ->
                    FilterChip(
                        selected = settings.contextTokens == n,
                        onClick = {
                            update { it.copy(contextTokens = n) }
                            scope.launch { ctx.app.engine.unload(); runCatching { ctx.app.engine.ensureLoaded() } }
                        },
                        label = { Text("${n / 1024}K") },
                    )
                }
            }
        }
    }
}

@Composable
fun BackendSwitcher(selected: ComputeBackend, model: ModelEntry, onSelect: (ComputeBackend) -> Unit, modifier: Modifier = Modifier) {
    val options = ComputeBackend.entries
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, b ->
            val supported = model.supports(b)
            ToggleButton(
                checked = b == model.effectiveBackend(selected),
                onCheckedChange = { onSelect(b) },
                enabled = supported,
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Icon(
                    when (b) {
                        ComputeBackend.CPU -> Icons.Rounded.Memory
                        ComputeBackend.GPU -> Icons.Rounded.DeveloperBoard
                        ComputeBackend.NPU -> Icons.Rounded.Bolt
                    },
                    null, Modifier.size(18.dp),
                )
                Text(b.label, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

private fun chipName(): String = when (Build.SOC_MODEL.uppercase()) {
    "SM8750" -> "Snapdragon 8 Elite"
    "SM8850" -> "Snapdragon 8 Elite Gen 5"
    "SM8650" -> "Snapdragon 8 Gen 3"
    "SM8550" -> "Snapdragon 8 Gen 2"
    else -> Build.SOC_MODEL
}

private fun backendBlurb(b: ComputeBackend, model: ModelEntry, engine: EngineState): String {
    val running = (engine as? EngineState.Ready)?.takeIf { it.model.id == model.id }?.backend
    val now = if (running != null && running != b) " Currently running on ${running.label}." else ""
    return when (b) {
        ComputeBackend.CPU -> "Slowest and warmest, but works with most models.$now"
        ComputeBackend.GPU -> "Adreno GPU on your ${chipName()}. Fast, the best default.$now"
        ComputeBackend.NPU -> if (model.supports(ComputeBackend.NPU)) {
            "Hexagon NPU on your ${chipName()}. Fastest and easiest on the battery." +
                (if (model.npuOnly) " This build was made for the NPU, so it always runs there." else "") + now
        } else {
            "The NPU needs a build made for your chip (look for files ending _qualcomm_${Build.SOC_MODEL.lowercase()}).$now"
        }
    }
}
