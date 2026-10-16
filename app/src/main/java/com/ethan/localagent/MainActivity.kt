package com.ethan.localagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethan.localagent.ui.AppRoot
import com.ethan.localagent.ui.theme.LocalAgentTheme

class MainActivity : ComponentActivity() {
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingRoute = intent.getStringExtra(EXTRA_ROUTE)
        setContent {
            val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            LocalAgentTheme(mode = s.theme, dynamic = s.dynamicColor) {
                AppRoot(settings = s, pendingRoute = pendingRoute, onRouteConsumed = { pendingRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute = it }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
