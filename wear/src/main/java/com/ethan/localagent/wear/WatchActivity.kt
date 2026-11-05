package com.ethan.localagent.wear

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The long-text complication opens straight into listening.
        val listenNow = intent?.getBooleanExtra(EXTRA_LISTEN, false) == true
        setContent { ChattifierWatchTheme { WatchApp(listenNow) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LISTEN, false)) recreate()
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
    }
}

// Fallback when the watch has no dynamic colour: the phone app's teal scheme (app/…/ui/theme/Theme.kt, dark).
private val Teal = ColorScheme(
    primary = Color(0xFF82D5C8), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFF9EF2E4),
    secondary = Color(0xFFB1CCC6), onSecondary = Color(0xFF1C3531),
    tertiary = Color(0xFFADCAE6), onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4961), onTertiaryContainer = Color(0xFFCCE5FF),
    surfaceContainerLow = Color(0xFF161D1B), surfaceContainer = Color(0xFF1A211F), surfaceContainerHigh = Color(0xFF252B2A),
    onSurface = Color(0xFFDDE4E1), onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4946),
    background = Color.Black, onBackground = Color(0xFFDDE4E1),
)

/** Material 3 for Wear OS, coloured from the watch's own theme (Wear OS 6+) like the phone app's dynamic colour. */
@Composable
private fun ChattifierWatchTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(colorScheme = remember { dynamicColorScheme(context) } ?: Teal, content = content)
}

/** The phone app's "cookie" shape: a circle with 12 soft scallops. */
private val Cookie = GenericShape { size, _ ->
    val cx = size.width / 2; val cy = size.height / 2
    val r = minOf(cx, cy)
    val steps = 240
    for (i in 0..steps) {
        val a = 2 * PI * i / steps
        val rr = r * (0.92f + 0.08f * cos(12 * a).toFloat())
        val x = cx + rr * cos(a).toFloat(); val y = cy + rr * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun WatchApp(listenNow: Boolean, vm: WatchViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text != null) vm.ask(text, byVoice = true, followUp = ui.screen == WatchScreen.Reply)
    }
    val keyboard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()
        if (text != null) vm.ask(text, byVoice = false, followUp = ui.screen == WatchScreen.Reply)
    }
    fun listen() {
        speech.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Chattifier"),
        )
    }
    fun type() {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(RemoteInput.Builder(INPUT_KEY).setLabel("Ask Chattifier").build()),
        )
        intent.putExtra("android.support.wearable.input.extra.INPUT_ACTION_TYPE", EditorInfo.IME_ACTION_SEND)
        keyboard.launch(intent)
    }
    LaunchedEffect(listenNow) { if (listenNow) listen() }

    // Swipe-back goes home from anywhere else; from home it leaves the app as usual.
    BackHandler(enabled = ui.screen != WatchScreen.Home) { vm.home() }

    AppScaffold(timeText = { TimeText() }) {
        when (ui.screen) {
            WatchScreen.Home -> HomeScreen(
                ui, onSpeak = ::listen, onType = ::type, onRetry = { vm.refresh() },
                onHistory = vm::showHistory, onContinue = { vm.resume() },
            )
            WatchScreen.Thinking -> ThinkingScreen(ui)
            WatchScreen.Reply -> ReplyScreen(ui, onStop = vm::stopSpeaking, onSpeak = ::listen, onType = ::type, onNewChat = vm::newChat)
            WatchScreen.Offline -> OfflineScreen(onRetry = vm::retryNow, onBack = vm::home)
            WatchScreen.History -> HistoryScreen(ui.history, onOpen = vm::open, onClear = vm::clearHistory)
        }
    }
}

@Composable
private fun HomeScreen(ui: WatchUi, onSpeak: () -> Unit, onType: () -> Unit, onRetry: () -> Unit, onHistory: () -> Unit, onContinue: () -> Unit) {
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(top = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val status = when {
                    ui.phoneModel == null -> "Finding phone…"
                    !ui.phoneReachable -> "Phone out of reach"
                    !ui.phoneEnabled -> "Turned off on phone"
                    ui.phoneModel.isBlank() -> "Phone · no model yet"
                    else -> "Phone · ${ui.phoneModel}"
                }
                val ok = ui.phoneReachable && ui.phoneEnabled
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(onClick = onRetry).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalIconButton(onClick = onHistory) { Icon(Icons.Rounded.History, "History") }
                    Box(
                        Modifier.size(84.dp).clip(Cookie).background(MaterialTheme.colorScheme.primary)
                            .clickable(role = Role.Button, onClickLabel = "Speak", onClick = onSpeak),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Mic, "Speak to Chattifier", Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    FilledTonalIconButton(onClick = onContinue, enabled = ui.history.isNotEmpty()) {
                        Icon(Icons.Rounded.Forum, "Continue last chat")
                    }
                }
                Text("Tap to speak", style = MaterialTheme.typography.titleMedium)
            }
            EdgeButton(
                onClick = onType,
                modifier = Modifier.align(Alignment.BottomCenter),
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Icon(Icons.Rounded.Keyboard, null, Modifier.size(20.dp))
                Text("Type", Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun ThinkingScreen(ui: WatchUi) {
    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(64.dp).clip(Cookie).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(40.dp))
            }
            Text("“${ui.question}”", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Smartphone, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Thinking on your phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReplyScreen(ui: WatchUi, onStop: () -> Unit, onSpeak: () -> Unit, onType: () -> Unit, onNewChat: () -> Unit) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Opening a long thread starts at the newest answer.
    LaunchedEffect(ui.thread.size) { if (ui.thread.isNotEmpty()) scroll.scrollTo(scroll.maxValue) }
    ScreenScaffold(scrollState = scroll) {
        Column(
            Modifier.fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = scroll), focusRequester = focus)
                .verticalScroll(scroll)
                .padding(start = 22.dp, end = 22.dp, top = 36.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val meta = buildString {
                append(ui.phoneModel?.takeIf { it.isNotBlank() } ?: "Phone")
                append(" on phone")
                ui.seconds?.let { append(" · %.1f s".format(it)) }
            }
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            ui.thread.forEach { turn ->
                Question(turn.q)
                Answer(turn.a, error = false)
            }
            Question(ui.question)
            Answer(ui.reply.ifBlank { "…" }, ui.error)
            if (ui.speaking) Text("Speaking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.speaking) FilledTonalIconButton(onClick = onStop) { Icon(Icons.Rounded.Stop, "Stop speaking") }
                else FilledTonalIconButton(onClick = onNewChat) { Icon(Icons.Rounded.Add, "New chat") }
                FilledIconButton(onClick = onSpeak) { Icon(Icons.Rounded.Mic, "Reply by voice") }
                FilledTonalIconButton(onClick = onType) { Icon(Icons.Rounded.Keyboard, "Reply by typing") }
            }
        }
    }
}

@Composable
private fun Question(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 10.dp, vertical = 6.dp),
)

@Composable
private fun Answer(text: String, error: Boolean) = Text(
    text, style = MaterialTheme.typography.bodyLarge,
    color = if (error) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
        .background(if (error) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer)
        .padding(12.dp),
)

@Composable
private fun HistoryScreen(history: List<Conversation>, onOpen: (Conversation) -> Unit, onClear: () -> Unit) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = list, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("History", style = MaterialTheme.typography.titleMedium) }
            if (history.isEmpty()) item {
                Text(
                    "Nothing yet. Your chats show up here.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
            items(history, key = { it.id }) { c ->
                Button(
                    onClick = { onOpen(c) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(ago(c.updatedAt) + " · " + plural(c.turns.size), maxLines = 1) },
                    label = { Text(c.title, maxLines = 2) },
                )
            }
            if (history.isNotEmpty()) item {
                Button(
                    onClick = onClear,
                    colors = ButtonDefaults.outlinedButtonColors(),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    icon = { Icon(Icons.Rounded.DeleteSweep, null) },
                    label = { Text("Clear history") },
                )
            }
        }
    }
}

private fun plural(n: Int) = if (n == 1) "1 question" else "$n questions"

private fun ago(at: Long): String {
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "Just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} d ago"
    }
}

@Composable
private fun OfflineScreen(onRetry: () -> Unit, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    ScreenScaffold(scrollState = scroll) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PhonelinkErase, null, tint = MaterialTheme.colorScheme.onTertiaryContainer) }
            Text("Can't reach your phone", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                "The AI runs on your phone. Keep it within Bluetooth range. No mobile data or Wi‑Fi needed.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry, icon = { Icon(Icons.Rounded.Refresh, null) }, label = { Text("Try again") })
            Text("Your question will send when it reconnects", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to home") }
        }
    }
}

private const val INPUT_KEY = "question"
