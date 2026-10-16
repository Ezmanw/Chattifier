package com.ethan.localagent.ui.onboarding

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ethan.localagent.adb.ActivationStep
import com.ethan.localagent.adb.Fix
import com.ethan.localagent.ui.voice.MorphingShape
import com.ethan.localagent.ui.voice.VoicePhase

@Composable
fun WelcomeScreen(onGetModel: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MorphingShape(phase = VoicePhase.Idle, level = 0f, onTap = {}, modifier = Modifier.size(180.dp))
        Spacer(Modifier.size(40.dp))
        Text("Your AI, on your phone", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
        Text(
            "Chat, talk, show it photos, audio and video, and let it code in the background. Nothing leaves this device.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
        )
        Button(onClick = onGetModel, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("Pick a model") }
        TextButton(onClick = onSkip) { Text("Look around first") }
    }
}


@Composable
fun ActivationScreen(
    step: ActivationStep,
    fixes: List<Fix>,
    log: List<String>,
    onPair: () -> Unit,
    onOpenWireless: () -> Unit,
    onRetry: () -> Unit,
    onSkipFix: () -> Unit,
    onFinish: () -> Unit,
    onManual: () -> Unit,
) {
    val ctx = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                ActivationStep.Pair, ActivationStep.WaitingForCode -> {
                    Text("BACKGROUND MODE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Unlock background mode", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Android stops apps that work with the screen off. Pair once over wireless debugging and the app switches those limits off by itself. No computer needed.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StepRow(1, "Turn on Developer options", "Settings › About phone › tap Build number 7 times") {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_DEVICE_INFO_SETTINGS))
                    }
                    StepRow(2, "Turn on Wireless debugging", "Developer options › Wireless debugging · needs Wi‑Fi", onOpenWireless)
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Badge(3)
                                Column {
                                    Text("Pair with code", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Tap Start, then in Wireless debugging choose Pair device with pairing code. Type the 6 digits into our notification. You don't need to leave that screen.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (step == ActivationStep.WaitingForCode) {
                                Row(Modifier.padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    androidx.compose.material3.LoadingIndicator(Modifier.size(28.dp))
                                    Text("Found it. Type the code into the notification.", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                Row(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onPair(); onOpenWireless() }) { Text("Start pairing") }
                                }
                            }
                        }
                    }
                }
                is ActivationStep.Applying, is ActivationStep.Failed -> {
                    val failed = step is ActivationStep.Failed
                    Text("STEP 4 OF 4", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(if (failed) "One fix needs a hand" else "Turning off battery limits", style = MaterialTheme.typography.headlineMedium)
                    if (step is ActivationStep.Applying) {
                        LinearWavyProgressIndicator(progress = { step.done.toFloat() / step.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Row { Text(step.current, Modifier.weight(1f)); Text("${step.done + 1} of ${step.total}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (step is ActivationStep.Failed) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(20.dp)) {
                            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Rounded.Error, null); Text(step.message, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    TextButton(onClick = { showLogs = !showLogs }) {
                        Icon(Icons.Rounded.Terminal, null, Modifier.size(18.dp)); Text(if (showLogs || failed) "  Hide logs" else "  Show logs")
                    }
                    AnimatedVisibility(showLogs || failed) { LogBox(log) }
                }
                ActivationStep.Done -> {
                    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                    }
                    Text("Background mode is on", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            fixes.forEach { f ->
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(f.command, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(if (f.ok == false) Icons.Rounded.Error else Icons.Rounded.CheckCircle, null,
                                        tint = if (f.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            when (step) {
                ActivationStep.Pair, ActivationStep.WaitingForCode -> { TextButton(onClick = onManual) { Text("Do it manually") }; TextButton(onClick = onFinish) { Text("Not now") } }
                is ActivationStep.Failed -> {
                    if (step.fixIndex != null) TextButton(onClick = onSkipFix) { Text("Skip this fix") } else TextButton(onClick = onFinish) { Text("Not now") }
                    Button(onClick = onRetry) { Text("Retry") }
                }
                is ActivationStep.Applying -> Text("Keep this screen open for a few seconds", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                ActivationStep.Done -> Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Start coding"); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 8.dp)) }
            }
        }
    }
}

@Composable
private fun Badge(n: Int) = Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp)) {
    Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun StepRow(n: Int, title: String, subtitle: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelMedium) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open", Modifier.size(18.dp)) }
    }
}

@Composable
fun LogBox(lines: List<String>) {
    Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            lines.takeLast(200).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            if (lines.isEmpty()) Text("(no output yet)", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
