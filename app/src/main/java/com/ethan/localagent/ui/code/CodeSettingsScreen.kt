package com.ethan.localagent.ui.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ethan.localagent.app
import com.ethan.localagent.data.PermissionMode
import com.ethan.localagent.data.Settings
import kotlinx.coroutines.launch

@Composable
fun CodeSettingsScreen(settings: Settings, onBack: () -> Unit, onOpenActivation: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(f: (Settings) -> Settings) = scope.launch { ctx.app.prefs.update(f) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Code settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Permissions")
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(4.dp)) {
                    PermissionMode.entries.forEach { mode ->
                        val on = settings.permissionMode == mode
                        Surface(
                            color = if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { update { it.copy(permissionMode = mode) } },
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = on, onClick = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(mode.title, style = MaterialTheme.typography.titleSmall)
                                    Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (settings.permissionMode == PermissionMode.ALWAYS) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Warning, null)
                        Text(
                            "The agent will edit files and run any command without asking. Use it only on folders you trust. " +
                                "It still can't write outside the project folder.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text("Always allowed commands", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.allowedCommands.sorted().forEach { cmd ->
                    InputChip(
                        selected = false,
                        onClick = { update { it.copy(allowedCommands = it.allowedCommands - cmd) } },
                        label = { Text(cmd, fontFamily = FontFamily.Monospace) },
                        trailingIcon = { Icon(Icons.Rounded.Close, "Remove $cmd") },
                    )
                }
                InputChip(selected = false, onClick = { adding = true }, label = { Text("Add") }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
            }

            Section("Background")
            SwitchRow("Keep working with the screen off", "Shows a notification while it runs", settings.keepWorking) { v -> update { it.copy(keepWorking = v) } }
            SwitchRow("Notify when it needs me", "Approve commands from the notification", settings.notifyApprovals) { v -> update { it.copy(notifyApprovals = v) } }

            Section("Environment")
            NavRow(Icons.Rounded.Terminal, "Built-in Linux shell", "Alpine Linux · git, python, node, ripgrep") { }
            NavRow(Icons.Rounded.Adb, "Background access", "Wireless-debugging activation and battery fixes", onOpenActivation)
        }
    }

    if (adding) {
        var cmd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Always allow a command") },
            text = { OutlinedTextField(cmd, { cmd = it.trim() }, label = { Text("e.g. npm") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (cmd.isNotBlank()) update { it.copy(allowedCommands = it.allowedCommands + cmd) }; adding = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp))

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
