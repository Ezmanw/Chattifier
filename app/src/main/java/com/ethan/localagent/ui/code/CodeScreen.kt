package com.ethan.localagent.ui.code

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethan.localagent.agent.AgentEvent
import com.ethan.localagent.agent.Approval
import com.ethan.localagent.agent.CodeSessionSummary
import com.ethan.localagent.agent.Decision
import com.ethan.localagent.app
import com.ethan.localagent.data.Settings
import com.ethan.localagent.shell.ShellState
import com.ethan.localagent.ui.components.AttachmentThumb
import com.ethan.localagent.ui.components.MarkdownText
import com.ethan.localagent.ui.onboarding.LogBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Turns a SAF tree URI on primary storage into the real path the Linux shell can use. */
fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val (volume, rel) = docId.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val root = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/$volume"
    return if (rel.isEmpty()) root else "$root/$rel"
}

@Composable
fun CodeScreen(settings: Settings, onOpenSettings: () -> Unit, onOpenActivation: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val agent = ctx.app.agent
    val state by agent.state.collectAsStateWithLifecycle()
    val shell by ctx.app.shell.state.collectAsStateWithLifecycle()
    val shellLog by ctx.app.shell.log.collectAsStateWithLifecycle()
    val sessions by ctx.app.codeSessions.summaries.collectAsStateWithLifecycle()
    var task by rememberSaveable { mutableStateOf("") }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    val models by ctx.app.models.models.collectAsStateWithLifecycle()
    // The agent needs text + tool calling, so speech-to-speech models aren't offered here.
    val codeModels = models.filterNot { it.speaksForItself }
    val codeModel = codeModels.firstOrNull { it.id == settings.codeModelId } ?: codeModels.firstOrNull { it.id == settings.activeModelId } ?: codeModels.firstOrNull()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUriToPath(it) }?.let { path -> scope.launch { ctx.app.prefs.update { s -> s.copy(codeFolder = path) } } }
    }
    val allFiles = Environment.isExternalStorageManager()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Code") },
                actions = {
                    Box {
                        TextButton(onClick = { showModels = true }, enabled = !state.running) {
                            Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(codeModel?.name?.take(16) ?: "Model", maxLines = 1)
                        }
                        DropdownMenu(expanded = showModels, onDismissRequest = { showModels = false }, shape = RoundedCornerShape(16.dp)) {
                            Text("CODE MODEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            if (codeModels.isEmpty()) DropdownMenuItem(text = { Text("No text model installed") }, onClick = {}, enabled = false)
                            codeModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.name) },
                                    leadingIcon = { if (m.id == codeModel?.id) Icon(Icons.Rounded.Check, null) else Box(Modifier.size(24.dp)) },
                                    onClick = {
                                        showModels = false
                                        scope.launch { ctx.app.prefs.update { it.copy(codeModelId = m.id) } }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showSessions = true }) { Icon(Icons.Rounded.History, "Code sessions") }
                    if (!state.running && state.events.isNotEmpty()) IconButton(onClick = agent::clear) { Icon(Icons.Rounded.DeleteSweep, "Clear session") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Code settings") }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            FolderCard(settings, state.running, state.startedAt, onPick = { pickFolder.launch(null) }, onOpenSettings = onOpenSettings)

            val list = rememberLazyListState()
            LaunchedEffect(state.events.size, (state.events.lastOrNull() as? AgentEvent.Reply)?.text?.length, state.approval) {
                if (state.events.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
            }
            LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                if (!allFiles) item {
                    InfoCard(
                        Icons.Rounded.FolderOpen, "Let the agent reach your folders",
                        "Code mode works on real files, so it needs All files access. Background activation grants it for you.",
                        action = "Grant" to { ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) },
                    )
                }
                if (!settings.onboardingDone) item {
                    InfoCard(Icons.Rounded.Shield, "Keep working with the screen off", "Unlock background mode once so Android doesn't stop long tasks.", action = "Set up" to onOpenActivation)
                }
                if (shell != ShellState.Ready && shell != ShellState.Unsupported) item { ShellCard(shell, shellLog, onInstall = ctx.app.shell::install) }
                if (state.events.isEmpty() && shell == ShellState.Ready) item { EmptyState() }
                items(state.events, key = { it.id }) { e -> EventRow(e) }
                state.approval?.let { a -> item(key = "approval") { ApprovalCard(a, onDecide = agent::decide) } }
                if (state.running && state.approval == null && (state.events.lastOrNull() as? AgentEvent.Reply)?.text.isNullOrEmpty()) {
                    item(key = "busy") { LoadingIndicator(Modifier.size(40.dp)) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        if (task.isEmpty()) Text(if (state.running) "Add a note…" else "Tell it what to do", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        BasicTextField(
                            task, { task = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 5, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state.running) {
                    FilledIconButton(
                        onClick = { agent.sendNote(task.trim()); task = "" },
                        enabled = task.isNotBlank(),
                        modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send note") }
                    FilledIconButton(onClick = agent::stop, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                        Icon(Icons.Rounded.Stop, "Stop agent")
                    }
                } else {
                    FilledIconButton(
                        onClick = { agent.start(task.trim()); task = "" },
                        enabled = task.isNotBlank() && settings.codeFolder != null,
                        modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    ) { Icon(Icons.Rounded.ArrowUpward, "Send") }
                }
            }
        }
    }

    if (showSessions) {
        SessionDialog(
            sessions = sessions,
            currentId = state.sessionId,
            onOpen = { id -> agent.openSession(id); showSessions = false },
            onNew = { agent.newSession(settings.codeFolder); showSessions = false },
            onDelete = { id -> agent.deleteSession(id) },
            onRename = { id, title -> agent.renameSession(id, title) },
            onDismiss = { showSessions = false },
        )
    }
}

@Composable
private fun SessionDialog(
    sessions: List<CodeSessionSummary>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<CodeSessionSummary?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Code sessions") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sessions.isEmpty()) {
                    Text("No sessions yet. Send a task to create one.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                sessions.forEach { s ->
                    Surface(
                        color = if (s.id == currentId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { renaming = s }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Edit, "Rename", Modifier.size(18.dp)) }
                            IconButton(onClick = { onDelete(s.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.DeleteSweep, "Delete", Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("New session") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )

    renaming?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename session") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { onRename(s.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FolderCard(settings: Settings, running: Boolean, startedAt: Long, onPick: () -> Unit, onOpenSettings: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(1000) } }
    Card(
        onClick = onPick, enabled = !running,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer, disabledContentColor = MaterialTheme.colorScheme.onSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    val folder = settings.codeFolder
                    Text(folder?.substringAfterLast('/') ?: "Choose a project folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        folder?.replace("/storage/emulated/0", "~/storage/shared") ?: "The agent can only touch files inside it",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!running) Icon(Icons.Rounded.SwapHoriz, "Change folder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = if (running) {
                    val s = (now - startedAt) / 1000
                    "Working in background · %dm %02ds".format(s / 60, s % 60)
                } else "Idle"
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                AssistChip(
                    onClick = onOpenSettings,
                    label = { Text(settings.permissionMode.title) },
                    leadingIcon = { Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    border = null,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {}
        Spacer(Modifier.size(20.dp))
        Text("Your on-device coding agent", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "It reads, edits and runs code in the folder above with a Linux shell built into the app, and keeps going with the screen off.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String, action: Pair<String, () -> Unit>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
                Button(onClick = action.second, modifier = Modifier.padding(top = 8.dp)) { Text(action.first) }
            }
        }
    }
}

@Composable
private fun ShellCard(state: ShellState, log: List<String>, onInstall: () -> Unit) {
    var showLogs by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                Text("Built-in Linux shell", style = MaterialTheme.typography.titleMedium)
            }
            when (state) {
                ShellState.NotInstalled -> {
                    Text("A small Alpine Linux with bash, git, python, node and ripgrep, so the agent can run your project's commands. About 150 MB.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onInstall) { Text("Install") }
                }
                is ShellState.Installing -> {
                    if (state.progress != null) LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.step, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Hide logs" else "Show logs") }
                }
                is ShellState.Failed -> {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(state.message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onInstall) { Text("Retry") }
                }
                else -> {}
            }
            AnimatedVisibility(showLogs || state is ShellState.Failed) { LogBox(log) }
        }
    }
}

@Composable
private fun EventRow(e: AgentEvent) {
    when (e) {
        is AgentEvent.Task -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Text(e.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        is AgentEvent.Reply -> if (e.text.isNotBlank()) MarkdownText(e.text)
        is AgentEvent.Note -> Text(e.text, style = MaterialTheme.typography.bodySmall, color = if (e.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        is AgentEvent.Tool -> ToolRow(e)
    }
}

@Composable
private fun ToolRow(t: AgentEvent.Tool) {
    var open by remember { mutableStateOf(false) }
    val icon = when (t.name) {
        "read_file", "list_files", "take_screenshot" -> Icons.Rounded.Visibility
        "search" -> Icons.Rounded.Search
        "write_file", "edit_file" -> Icons.Rounded.Edit
        else -> Icons.Rounded.Terminal
    }
    Column(Modifier.fillMaxWidth().clickable(enabled = t.output.isNotEmpty()) { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (t.status) {
                AgentEvent.Tool.Status.Running, AgentEvent.Tool.Status.Waiting -> LoadingIndicator(Modifier.size(20.dp))
                AgentEvent.Tool.Status.Failed -> Icon(Icons.Rounded.Error, "Failed", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                AgentEvent.Tool.Status.Denied -> Icon(Icons.Rounded.Block, "Denied", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                AgentEvent.Tool.Status.Done -> Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(t.summary, style = MaterialTheme.typography.bodyMedium, fontFamily = if (t.name == "run_command") FontFamily.Monospace else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            t.diff?.let { d ->
                val (add, del) = d.split(' ').let { it[0] to it.getOrElse(1) { "" } }
                Text(add, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Text(del, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (t.attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                items(t.attachments) { AttachmentThumb(it, size = 220) }
            }
        }
        AnimatedVisibility(open || (t.name == "run_command" && t.status != AgentEvent.Tool.Status.Waiting && t.output.isNotEmpty())) {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(
                    t.output.lines().takeLast(if (open) 400 else 12).joinToString("\n"),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(a: Approval, onDecide: (Decision) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GppMaybe, null, tint = MaterialTheme.colorScheme.tertiary)
                Text(if (a.command != null) "Allow this command?" else "Allow this change?", style = MaterialTheme.typography.titleSmall)
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                Text(a.detail.take(1500), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp).heightIn(max = 220.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { onDecide(Decision.DENY) }) { Text("Deny") }
                OutlinedButton(onClick = { onDecide(Decision.ALWAYS) }) {
                    Text(if (a.command != null) "Always allow ${a.command.trim().substringBefore(' ')}" else "Allow all edits", maxLines = 1)
                }
                Button(onClick = { onDecide(Decision.ALLOW) }) { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)); Text(" Allow") }
            }
        }
    }
}
