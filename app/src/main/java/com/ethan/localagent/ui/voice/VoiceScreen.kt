package com.ethan.localagent.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ethan.localagent.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The M3 Expressive shapes the loading indicator cycles through, in the same order. */
private val SHAPES = listOf(
    MaterialShapes.SoftBurst, MaterialShapes.Cookie9Sided, MaterialShapes.Pentagon, MaterialShapes.Pill,
    MaterialShapes.Sunny, MaterialShapes.Cookie4Sided, MaterialShapes.Oval,
)

@Composable
fun VoiceScreen(onBack: () -> Unit, vm: VoiceViewModel = viewModel()) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val micLevel by vm.level.collectAsStateWithLifecycle()
    val voiceLevel by vm.outputLevel.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val pack by vm.voicePack.collectAsStateWithLifecycle()
    val settings by ctx.app.prefs.settings.collectAsStateWithLifecycle(null)
    val engine by ctx.app.engine.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.start() }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { vm.stopAll() } }

    val voiceLabel = voices.firstOrNull { it.id == settings?.voiceName }?.label ?: voices.firstOrNull()?.label ?: "System voice"
    val modelName = (engine as? com.ethan.localagent.engine.EngineState.Ready)?.model?.name ?: "Loading model…"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to chat") } },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Voice", style = MaterialTheme.typography.titleMedium)
                        Text(if (ui.ownVoice) modelName else "$modelName · $voiceLabel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Tune, "Voice settings") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                            Text("VOICE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            if (ui.ownVoice) {
                                DropdownMenuItem(text = { Text("This model speaks in its own voice — no voice to pick") }, onClick = {}, enabled = false)
                            }
                            if (!ui.ownVoice) when (val p = pack) {
                                com.ethan.localagent.util.VoicePackState.Missing, is com.ethan.localagent.util.VoicePackState.Failed -> DropdownMenuItem(
                                    text = { Column { Text("Get natural voices"); Text(if (p is com.ethan.localagent.util.VoicePackState.Failed) p.message else "Kokoro · 11 voices · 350 MB", style = MaterialTheme.typography.labelSmall) } },
                                    leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                    onClick = vm::downloadVoices,
                                )
                                is com.ethan.localagent.util.VoicePackState.Downloading -> DropdownMenuItem(
                                    text = { Text("Downloading voices… ${(p.progress * 100).toInt()}%") },
                                    leadingIcon = { androidx.compose.material3.CircularWavyProgressIndicator(progress = { p.progress }, modifier = Modifier.size(24.dp)) },
                                    onClick = {}, enabled = false,
                                )
                                else -> {}
                            }
                            if (!ui.ownVoice) {
                                if (voices.isEmpty()) DropdownMenuItem(text = { Text("System default") }, onClick = {}, enabled = false)
                                voices.forEach { v ->
                                    val selected = v.id == settings?.voiceName || (settings?.voiceName == null && v == voices.first())
                                    DropdownMenuItem(
                                        text = { Text(v.label) },
                                        trailingIcon = { Text(v.hint, style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = { if (selected) Icon(Icons.Rounded.Check, null) else Box(Modifier.size(24.dp)) },
                                        onClick = { vm.setVoice(v.id) },
                                    )
                                }
                                HorizontalDivider()
                                Text("SPEED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                listOf(0.8f, 1.0f, 1.2f, 1.5f).forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text("%.1f×".format(r)) },
                                        leadingIcon = { if (settings?.speechRate == r) Icon(Icons.Rounded.Check, null) else Box(Modifier.size(24.dp)) },
                                        onClick = { vm.setRate(r) },
                                    )
                                }
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text("Talk over to interrupt") },
                                trailingIcon = { Switch(checked = settings?.talkOver ?: true, onCheckedChange = null) },
                                onClick = { vm.setTalkOver(!(settings?.talkOver ?: true)) },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ui.captions) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ui.transcript) { m ->
                        Text(
                            m.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (m.role == com.ethan.localagent.data.Role.USER) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    MorphingShape(phase = ui.phase, level = if (ui.phase == VoicePhase.Speaking) voiceLevel else micLevel, onTap = vm::tapShape, modifier = Modifier.size(260.dp))
                    Text(
                        when (ui.phase) {
                            VoicePhase.Idle -> "Starting…"
                            VoicePhase.Listening -> if (ui.muted) "Muted" else "Listening… tap to send"
                            VoicePhase.Paused -> "Paused · tap to talk"
                            VoicePhase.Thinking -> "Thinking…"
                            VoicePhase.Speaking -> "Speaking · tap to interrupt"
                        },
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                    if (ui.heard.isNotBlank()) {
                        Text(
                            "“${ui.heard}”", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        )
                    }
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp)) }
                }
            }
            if (ui.reply.isNotBlank() && !ui.captions) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 140.dp)) {
                    Text(ui.reply, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                }
            }
            Row(Modifier.padding(top = 24.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = vm::toggleMute, modifier = Modifier.size(56.dp)) {
                    Icon(if (ui.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (ui.muted) "Unmute" else "Mute")
                }
                FilledIconButton(
                    onClick = { vm.stopAll(); onBack() },
                    modifier = Modifier.size(width = 96.dp, height = 72.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) { Icon(Icons.Rounded.Close, "End voice mode", Modifier.size(32.dp)) }
                FilledTonalIconButton(onClick = vm::toggleCaptions, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Subtitles, if (ui.captions) "Hide transcript" else "Show transcript")
                }
            }
        }
    }
}

/**
 * The AI, drawn as one flat Material shape. It springs from shape to shape (M3 Expressive's
 * spatial spring), faster while thinking, and breathes with your voice while listening.
 */
@Composable
fun MorphingShape(phase: VoicePhase, level: Float, onTap: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    var index by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val morphs = remember { SHAPES.indices.map { i -> Morph(SHAPES[i], SHAPES[(i + 1) % SHAPES.size]) } }
    val springSpec = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)

    LaunchedEffect(phase) {
        val hold = when (phase) {
            VoicePhase.Thinking -> 250L
            VoicePhase.Speaking -> 700L
            VoicePhase.Listening -> 1800L
            VoicePhase.Idle, VoicePhase.Paused -> 2600L
        }
        while (true) {
            delay(hold)
            index = (index + 1) % SHAPES.size
            progress.snapTo(0f)
            launch { rotation.animateTo(rotation.value + 360f / SHAPES.size * 2, springSpec) }
            progress.animateTo(1f, springSpec)
        }
    }
    val breathe by animateFloatAsState(
        targetValue = when (phase) {
            VoicePhase.Listening -> 0.9f + level.coerceIn(0f, 1f) * 0.25f
            VoicePhase.Speaking -> 0.95f + level.coerceIn(0f, 1f) * 0.12f
            else -> 0.95f
        },
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "breathe",
    )
    val androidPath = remember { android.graphics.Path() }
    Canvas(modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)) {
        val morph = morphs[(index - 1 + SHAPES.size) % SHAPES.size]
        androidPath.rewind()
        morph.toPath(progress.value, androidPath)
        val path = androidPath.asComposePath()
        val s = size.minDimension * breathe
        path.transform(Matrix().apply { scale(s, s) })
        path.translate(androidx.compose.ui.geometry.Offset((size.width - s) / 2, (size.height - s) / 2))
        rotate(rotation.value) { drawPath(path, color) }
    }
}
