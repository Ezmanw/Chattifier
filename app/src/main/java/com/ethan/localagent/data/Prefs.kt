package com.ethan.localagent.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ComputeBackend(val label: String) { CPU("CPU"), GPU("GPU"), NPU("NPU") }

enum class PermissionMode(val title: String, val subtitle: String) {
    ASK("Ask first", "Approve every edit and command"),
    EDITS("Allow edits", "Edit files freely, ask before commands"),
    ALWAYS("Always allow", "Never ask. Fastest, least safe"),
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val activeModelId: String? = null,
    val backend: ComputeBackend = ComputeBackend.GPU,
    val temperature: Float = 0.8f,
    val contextTokens: Int = 4096,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val hfToken: String = "",
    val voiceName: String? = null,
    val speechRate: Float = 1.0f,
    val talkOver: Boolean = true,
    val permissionMode: PermissionMode = PermissionMode.ASK,
    val allowedCommands: Set<String> = setOf("git", "ls", "cat", "rg", "grep", "find"),
    val keepWorking: Boolean = true,
    val notifyApprovals: Boolean = true,
    val phoneControl: Boolean = false,
    val onboardingDone: Boolean = false,
    val codeFolder: String? = null,
    val codeModelId: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant running fully on the user's phone. Be concise and friendly."
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class Prefs(private val context: Context) {
    private object K {
        val activeModel = stringPreferencesKey("active_model")
        val backend = stringPreferencesKey("backend")
        val temperature = floatPreferencesKey("temperature")
        val contextTokens = intPreferencesKey("context_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val hfToken = stringPreferencesKey("hf_token")
        val voice = stringPreferencesKey("voice")
        val speechRate = floatPreferencesKey("speech_rate")
        val talkOver = booleanPreferencesKey("talk_over")
        val permissionMode = stringPreferencesKey("permission_mode")
        val allowed = stringSetPreferencesKey("allowed_commands")
        val keepWorking = booleanPreferencesKey("keep_working")
        val notifyApprovals = booleanPreferencesKey("notify_approvals")
        val phoneControl = booleanPreferencesKey("phone_control")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val codeFolder = stringPreferencesKey("code_folder")
        val codeModel = stringPreferencesKey("code_model")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            activeModelId = this[K.activeModel],
            backend = this[K.backend]?.let { runCatching { ComputeBackend.valueOf(it) }.getOrNull() } ?: d.backend,
            temperature = this[K.temperature] ?: d.temperature,
            contextTokens = this[K.contextTokens] ?: d.contextTokens,
            systemPrompt = this[K.systemPrompt] ?: d.systemPrompt,
            hfToken = this[K.hfToken] ?: "",
            voiceName = this[K.voice],
            speechRate = this[K.speechRate] ?: d.speechRate,
            talkOver = this[K.talkOver] ?: d.talkOver,
            permissionMode = this[K.permissionMode]?.let { runCatching { PermissionMode.valueOf(it) }.getOrNull() }
                ?: d.permissionMode,
            allowedCommands = this[K.allowed] ?: d.allowedCommands,
            keepWorking = this[K.keepWorking] ?: d.keepWorking,
            notifyApprovals = this[K.notifyApprovals] ?: d.notifyApprovals,
            phoneControl = this[K.phoneControl] ?: d.phoneControl,
            onboardingDone = this[K.onboarding] ?: false,
            codeFolder = this[K.codeFolder],
            codeModelId = this[K.codeModel],
            theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            dynamicColor = this[K.dynamicColor] ?: d.dynamicColor,
        )
    }

    suspend fun update(block: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = block(p.toSettings())
            fun <T> set(key: Preferences.Key<T>, value: T?) {
                if (value == null) p.remove(key) else p[key] = value
            }
            set(K.activeModel, s.activeModelId)
            set(K.backend, s.backend.name)
            set(K.temperature, s.temperature)
            set(K.contextTokens, s.contextTokens)
            set(K.systemPrompt, s.systemPrompt)
            set(K.hfToken, s.hfToken)
            set(K.voice, s.voiceName)
            set(K.speechRate, s.speechRate)
            set(K.talkOver, s.talkOver)
            set(K.permissionMode, s.permissionMode.name)
            set(K.allowed, s.allowedCommands)
            set(K.keepWorking, s.keepWorking)
            set(K.notifyApprovals, s.notifyApprovals)
            set(K.phoneControl, s.phoneControl)
            set(K.onboarding, s.onboardingDone)
            set(K.codeFolder, s.codeFolder)
            set(K.codeModel, s.codeModelId)
            set(K.theme, s.theme.name)
            set(K.dynamicColor, s.dynamicColor)
        }
    }
}
