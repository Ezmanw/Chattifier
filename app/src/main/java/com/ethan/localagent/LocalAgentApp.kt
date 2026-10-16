package com.ethan.localagent

import android.app.Application
import android.content.Context
import com.ethan.localagent.adb.AdbActivator
import com.ethan.localagent.agent.AgentController
import com.ethan.localagent.data.ChatRepository
import com.ethan.localagent.data.ModelRepository
import com.ethan.localagent.data.Prefs
import com.ethan.localagent.engine.EngineManager
import com.ethan.localagent.hf.DownloadManager
import com.ethan.localagent.hf.HuggingFace
import com.ethan.localagent.shell.LinuxShell
import com.ethan.localagent.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LocalAgentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
    }
}

/** Process-wide singletons. Small enough that a hand-written container beats a DI framework. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val prefs = Prefs(context)
    val models = ModelRepository(context, scope)
    val chats = ChatRepository(context, scope)
    val engine = EngineManager(context, prefs, models, http, scope)
    val hf = HuggingFace(http, prefs)
    val downloads = DownloadManager(context, http, prefs, models, scope)
    val shell = LinuxShell(context, http, scope)
    val adb = AdbActivator(context, scope)
    val agent = AgentController(context, prefs, engine, shell, scope)
    val speaker by lazy { com.ethan.localagent.util.Speaker(context, http) }
}

val Context.app: AppContainer get() = (applicationContext as LocalAgentApp).container
