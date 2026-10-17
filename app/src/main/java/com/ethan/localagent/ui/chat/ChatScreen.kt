package com.ethan.localagent.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ethan.localagent.app
import com.ethan.localagent.data.AttachmentKind
import com.ethan.localagent.data.ChatMessage
import com.ethan.localagent.data.ModelEntry
import com.ethan.localagent.data.Role
import com.ethan.localagent.data.Settings
import com.ethan.localagent.engine.EngineState
import com.ethan.localagent.ui.components.AttachmentThumb
import com.ethan.localagent.ui.components.MarkdownText
import com.ethan.localagent.util.Recorder
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun ChatScreen(
    settings: Settings,
    onOpenModels: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ChatViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val models by ctx.app.models.models.collectAsStateWithLifecycle()
    val active = models.firstOrNull { it.id == settings.activeModelId } ?: models.firstOrNull()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.error) {
        ui.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 12.dp))
                NavigationDrawerItem(
                    label = { Text("New chat") }, icon = { Icon(Icons.Rounded.EditNote, null) }, selected = false,
                    onClick = { vm.newChat(); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(summaries, key = { it.id }) { s ->
                        NavigationDrawerItem(
                            label = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = s.id == ui.chat.id,
                            onClick = { vm.open(s.id); scope.launch { drawer.close() } },
                            badge = {
                                IconButton(onClick = { vm.delete(s.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Delete, "Delete ${s.title}", Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                NavigationDrawerItem(
                    label = { Text("Settings") }, icon = { Icon(Icons.Rounded.Settings, null) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenSettings() },
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "Open chats") } },
                    title = {
                        TextButton(onClick = { showSheet = true }) {
                            Text(active?.name ?: "No model", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Rounded.ExpandMore, "Choose model", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = { IconButton(onClick = vm::newChat) { Icon(Icons.Rounded.EditNote, "New chat") } },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
                StatusStrip(active, engine, settings)
                Box(Modifier.weight(1f)) {
                    if (active == null) {
                        EmptyNoModel(onOpenModels)
                    } else if (ui.chat.messages.isEmpty()) {
                        EmptyChat(active)
                    } else {
                        Messages(ui.chat.messages, ui.generating, onRegenerate = vm::regenerate)
                    }
                }
                Composer(
                    model = active,
                    state = ui,
                    onSend = vm::send,
                    onStop = vm::stop,
                    onAttach = vm::attach,
                    onRecording = vm::attachRecording,
                    onRemove = vm::removePending,
                    onVoice = onOpenVoice,
                )
            }
        }
    }

    if (showSheet) {
        ModelSheet(settings = settings, models = models, engine = engine, onDismiss = { showSheet = false }, onManage = { showSheet = false; onOpenModels() })
    }
}

@Composable
private fun StatusStrip(model: ModelEntry?, engine: EngineState, settings: Settings) {
    if (model == null) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        item {
            val (label, loading) = when (engine) {
                is EngineState.Loading -> "Loading on ${engine.backend.label}…" to true
                is EngineState.Ready -> "On device · ${engine.backend.label}" to false
                is EngineState.Failed -> "Couldn't load" to false
                EngineState.Idle -> "On device · ${settings.backend.label}" to false
            }
            AssistChip(
                onClick = {},
                label = { Text(label) },
                leadingIcon = {
                    if (loading) LoadingIndicator(Modifier.size(18.dp)) else Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp))
                },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, labelColor = MaterialTheme.colorScheme.onSecondaryContainer),
                border = null,
            )
        }
        val caps = buildList {
            if (model.vision) add(Icons.Rounded.Image to "Vision")
            if (model.audio) add(Icons.Rounded.GraphicEq to "Audio")
            if (model.video) add(Icons.Rounded.Movie to "Video")
        }
        if (caps.isNotEmpty()) item {
            AssistChip(
                onClick = {},
                label = { Text(caps.joinToString(" · ") { it.second }) },
                leadingIcon = { Row { caps.forEach { Icon(it.first, null, Modifier.size(16.dp)) } } },
                border = null,
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
    }
    (engine as? EngineState.Ready)?.note?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    (engine as? EngineState.Failed)?.message?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun EmptyNoModel(onOpenModels: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        ContainedLoadingIndicator(Modifier.size(96.dp))
        Spacer(Modifier.size(24.dp))
        Text("No model yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Everything runs on your phone. Grab a model to get started.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Button(onClick = onOpenModels) { Text("Get a model") }
    }
}

@Composable
private fun EmptyChat(model: ModelEntry) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("What's on your mind?", style = MaterialTheme.typography.headlineMedium)
        val hint = buildList {
            if (model.vision) add("photos")
            if (model.audio) add("audio")
            if (model.video) add("video")
        }
        if (hint.isNotEmpty()) {
            Text(
                "${model.name} can take ${hint.joinToString(", ")} too.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Messages(messages: List<ChatMessage>, generating: Boolean, onRegenerate: () -> Unit) {
    val list = rememberLazyListState()
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1, scrollOffset = Int.MAX_VALUE / 2)
    }
    LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { m ->
            if (m.role == Role.USER) UserBubble(m) else AssistantMessage(m, streaming = generating && m === last, isLast = m === last, onRegenerate = onRegenerate)
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                items(m.attachments) { AttachmentThumb(it) }
            }
        }
        if (m.text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun AssistantMessage(m: ChatMessage, streaming: Boolean, isLast: Boolean, onRegenerate: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.text.isEmpty() && streaming) {
            LoadingIndicator(Modifier.size(40.dp))
        } else if (m.error) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(16.dp)) {
                Text(m.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            MarkdownText(m.text)
        }
        if (!streaming && m.text.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("reply", m.text))) } }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isLast) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Refresh, "Regenerate", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { ctx.app.speaker.speak(m.text) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.VolumeUp, "Read aloud", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val stats = listOfNotNull(m.tokensPerSecond?.let { "%.1f tok/s".format(it) }, m.backend).joinToString(" · ")
                if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun Composer(
    model: ModelEntry?,
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttach: (android.net.Uri, AttachmentKind) -> Unit,
    onRecording: (File) -> Unit,
    onRemove: (com.ethan.localagent.data.Attachment) -> Unit,
    onVoice: () -> Unit,
) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var attachOpen by rememberSaveable { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(scope) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> onAttach(u, AttachmentKind.IMAGE) } }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> onAttach(u, AttachmentKind.VIDEO) } }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> onAttach(u, AttachmentKind.AUDIO) } }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) onAttach(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f), AttachmentKind.IMAGE)
    }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = (text + " " + it).trim() }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { recorder.beginManual(); recording = true }
    }

    fun toggleMic() {
        if (model?.audio == true) {
            if (recording) {
                recording = false
                recorder.endManual(File(ctx.app.chats.attachmentsDir, "${UUID.randomUUID().toString().take(8)}.wav"))?.let(onRecording)
            } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                recorder.beginManual(); recording = true
            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            dictate.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
            )
        }
    }

    // Speech-to-speech models have no text channel to chat with — send people to Voice mode.
    if (model?.speaksForItself == true) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "${model.name} is a voice model — talk to it in Voice mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onVoice) { Text("Open Voice") }
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth()) {
        if (state.pending.isNotEmpty() || state.preparing) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.pending) { AttachmentThumb(it, size = 88, onRemove = { onRemove(it) }) }
                if (state.preparing) item { Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            }
        }
        AnimatedVisibility(attachOpen) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                item {
                    AssistChip(onClick = { attachOpen = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        label = { Text("Photo") }, leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)) }, enabled = model?.vision == true)
                }
                item {
                    AssistChip(onClick = {
                        attachOpen = false
                        val f = File(ctx.cacheDir, "captures/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
                        cameraFile = f
                        takePhoto.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
                    }, label = { Text("Camera") }, leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)) }, enabled = model?.vision == true)
                }
                item {
                    AssistChip(onClick = { attachOpen = false; pickAudio.launch("audio/*") },
                        label = { Text("Audio") }, leadingIcon = { Icon(Icons.Rounded.AudioFile, null, Modifier.size(18.dp)) }, enabled = model?.audio == true)
                }
                item {
                    AssistChip(onClick = { attachOpen = false; pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        label = { Text("Video") }, leadingIcon = { Icon(Icons.Rounded.Videocam, null, Modifier.size(18.dp)) }, enabled = model?.video == true)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = { attachOpen = !attachOpen }) {
                        Icon(if (attachOpen) Icons.Rounded.Close else Icons.Rounded.Add, if (attachOpen) "Close attach menu" else "Attach")
                    }
                    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        if (text.isEmpty()) {
                            Text(
                                if (recording) "Recording… tap the mic to finish" else "Message ${model?.name ?: ""}".trim(),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = text, onValueChange = { text = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(onClick = ::toggleMic, colors = if (recording) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error) else IconButtonDefaults.iconButtonColors()) {
                        Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (recording) "Stop recording" else "Dictate")
                    }
                }
            }
            when {
                state.generating -> FilledIconButton(onClick = onStop, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Stop, "Stop")
                }
                text.isNotBlank() || state.pending.isNotEmpty() -> FilledIconButton(
                    onClick = { onSend(text); text = ""; attachOpen = false },
                    modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp), enabled = model != null && !state.preparing,
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
                else -> FilledIconButton(
                    onClick = onVoice, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Icon(Icons.Rounded.GraphicEq, "Voice mode") }
            }
        }
    }
}

