package com.ethan.localagent.ui.wear

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethan.localagent.app
import com.ethan.localagent.data.Settings
import com.ethan.localagent.data.WearReply
import com.ethan.localagent.util.VoicePackState
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun WearablesScreen(settings: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(f: (Settings) -> Settings) = scope.launch { ctx.app.prefs.update(f) }
    val models by ctx.app.models.models.collectAsStateWithLifecycle()
    val pack by ctx.app.speaker.pack.collectAsStateWithLifecycle()
    val model = models.firstOrNull { it.id == settings.activeModelId } ?: models.firstOrNull()

    // null = still looking, empty = no watch paired or Play services missing.
    var watches by remember { mutableStateOf<List<Node>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            watches = runCatching { Wearable.getNodeClient(ctx).connectedNodes.await() }.getOrDefault(emptyList())
            delay(5_000)
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Wearables") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val watch = watches?.firstOrNull()
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.Watch, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Column {
                        Text(
                            when {
                                watches == null -> "Looking for your watch…"
                                watch == null -> "No watch connected"
                                else -> watch.displayName
                            },
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            if (watch != null) "Connected" + if (watch.isNearby) " · Bluetooth" else " · via Wi‑Fi"
                            else "Pair a Wear OS watch, then install Chattifier on it",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(
                        "Your watch talks straight to this phone. Works with no mobile data or Wi‑Fi, and nothing leaves your devices.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text("Watch requests", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
                Column {
                    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
                    ListItem(
                        headlineContent = { Text("Answer from watch") },
                        supportingContent = { Text("Runs in the background, even with the app closed") },
                        trailingContent = { Switch(settings.wearEnabled, { v -> update { it.copy(wearEnabled = v) } }) },
                        colors = clear,
                        modifier = Modifier.clickable { update { it.copy(wearEnabled = !it.wearEnabled) } },
                    )
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Reply on watch as", style = MaterialTheme.typography.bodyLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            WearReply.entries.forEachIndexed { i, r ->
                                SegmentedButton(
                                    selected = settings.wearReply == r,
                                    onClick = { update { it.copy(wearReply = r) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, WearReply.entries.size),
                                ) { Text(r.label) }
                            }
                        }
                    }
                    ListItem(
                        headlineContent = { Text("Model") },
                        supportingContent = { Text(model?.name?.let { "$it · same as chat" } ?: "None installed yet") },
                        colors = clear,
                    )
                    ListItem(
                        headlineContent = { Text("Voice") },
                        supportingContent = {
                            Text(
                                if (pack == VoicePackState.Ready && settings.voiceName?.startsWith("kokoro:") != false) "Kokoro, rendered on this phone"
                                else "The watch's own voice (get Kokoro in Voice mode)",
                            )
                        },
                        colors = clear,
                    )
                    ListItem(
                        headlineContent = { Text("Short answers") },
                        supportingContent = { Text("Keeps replies to two or three sentences") },
                        trailingContent = { Switch(settings.wearShort, { v -> update { it.copy(wearShort = v) } }) },
                        colors = clear,
                        modifier = Modifier.clickable { update { it.copy(wearShort = !it.wearShort) } },
                    )
                }
            }
        }
    }
}
