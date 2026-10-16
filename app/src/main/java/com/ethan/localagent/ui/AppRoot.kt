package com.ethan.localagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ethan.localagent.app
import com.ethan.localagent.data.Settings
import com.ethan.localagent.data.ThemeMode
import com.ethan.localagent.ui.chat.ChatScreen
import com.ethan.localagent.ui.code.CodeScreen
import com.ethan.localagent.ui.code.CodeSettingsScreen
import com.ethan.localagent.ui.models.ModelsScreen
import com.ethan.localagent.ui.onboarding.ActivationScreen
import com.ethan.localagent.ui.onboarding.WelcomeScreen
import com.ethan.localagent.ui.voice.VoiceScreen
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab("chat", "Chat", Icons.Outlined.Chat, Icons.Rounded.Chat),
    Tab("voice", "Voice", Icons.Rounded.GraphicEq, Icons.Rounded.GraphicEq),
    Tab("code", "Code", Icons.Outlined.Terminal, Icons.Rounded.Terminal),
    Tab("models", "Models", Icons.Outlined.Inventory2, Icons.Rounded.Inventory2),
)

@Composable
fun AppRoot(settings: Settings, pendingRoute: String?, onRouteConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val models by ctx.app.models.models.collectAsStateWithLifecycle()
    val start = remember { if (models.isEmpty() && !settings.onboardingDone) "welcome" else "chat" }

    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { nav.navigateTab(it); onRouteConsumed() }
    }

    Scaffold(
        bottomBar = {
            if (route in setOf("chat", "code", "models")) {
                NavigationBar {
                    TABS.forEach { t ->
                        val selected = route == t.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (t.route == "voice") nav.navigate("voice") else nav.navigateTab(t.route) },
                            icon = { Icon(if (selected) t.selectedIcon else t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable("welcome") {
                WelcomeScreen(onGetModel = { nav.navigate("models") { popUpTo("welcome") { inclusive = true } } }, onSkip = { nav.navigateTab("chat") })
            }
            composable("chat") {
                ChatScreen(settings, onOpenModels = { nav.navigateTab("models") }, onOpenVoice = { nav.navigate("voice") }, onOpenSettings = { nav.navigate("settings") })
            }
            composable("voice") { VoiceScreen(onBack = { nav.popBackStack() }) }
            composable("models") { ModelsScreen() }
            composable("code") { CodeScreen(settings, onOpenSettings = { nav.navigate("code_settings") }, onOpenActivation = { nav.navigate("activation") }) }
            composable("code_settings") { CodeSettingsScreen(settings, onBack = { nav.popBackStack() }, onOpenActivation = { nav.navigate("activation") }) }
            composable("settings") { SettingsScreen(settings, onBack = { nav.popBackStack() }) }
            composable("activation") {
                val adb = ctx.app.adb
                val step by adb.step.collectAsStateWithLifecycle()
                val fixes by adb.fixes.collectAsStateWithLifecycle()
                val log by adb.log.collectAsStateWithLifecycle()
                ActivationScreen(
                    step = step, fixes = fixes, log = log,
                    onPair = adb::start,
                    onOpenWireless = adb::openWirelessDebugging,
                    onRetry = adb::retry, onSkipFix = adb::skip,
                    onFinish = { if (!nav.popBackStack()) nav.navigateTab("code") },
                    onManual = {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}")),
                        )
                    },
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(f: (Settings) -> Settings) = scope.launch { ctx.app.prefs.update(f) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("Look", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(selected = settings.theme == m, onClick = { update { it.copy(theme = m) } },
                        label = { Text(m.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }, modifier = Modifier.padding(end = 8.dp))
                }
            }
            ListItem(
                headlineContent = { Text("Colours from wallpaper") },
                trailingContent = { Switch(settings.dynamicColor, { v -> update { it.copy(dynamicColor = v) } }) },
                modifier = Modifier.clickable { update { it.copy(dynamicColor = !it.dynamicColor) } },
            )
            Text("Chat", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            var prompt by remember { mutableStateOf(settings.systemPrompt) }
            OutlinedTextField(
                prompt, { prompt = it; update { s -> s.copy(systemPrompt = it) } },
                label = { Text("System prompt") }, minLines = 3,
                modifier = Modifier.padding(16.dp).fillMaxSize(),
            )
        }
    }
}
