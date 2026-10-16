package com.ethan.localagent.agent

import android.content.Context
import com.ethan.localagent.data.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A persistent code-mode session: its project folder plus the full agent transcript and UI events. */
@Serializable
data class CodeSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New task",
    val folder: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val events: List<AgentEvent> = emptyList(),
)

data class CodeSessionSummary(val id: String, val title: String, val updatedAt: Long)

private val json = Json { ignoreUnknownKeys = true }

/** Mirrors [com.ethan.localagent.data.ChatRepository], but for code-mode sessions. */
class CodeSessionRepository(context: Context, scope: CoroutineScope) {
    private val dir = File(context.filesDir, "code_sessions").apply { mkdirs() }
    private val _summaries = MutableStateFlow<List<CodeSessionSummary>>(emptyList())
    val summaries: StateFlow<List<CodeSessionSummary>> = _summaries.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun refresh() {
        _summaries.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString<CodeSession>(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
            .map { CodeSessionSummary(it.id, it.title, it.updatedAt) }
    }

    suspend fun load(id: String): CodeSession? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<CodeSession>(File(dir, "$id.json").readText()) }.getOrNull()
    }

    suspend fun save(session: CodeSession): CodeSession = withContext(Dispatchers.IO) {
        File(dir, "${session.id}.json").writeText(json.encodeToString(session))
        refresh()
        session
    }

    suspend fun create(folder: String?): CodeSession = withContext(Dispatchers.IO) {
        val s = CodeSession(folder = folder)
        File(dir, "${s.id}.json").writeText(json.encodeToString(s))
        refresh()
        s
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        load(id)?.events
            ?.filterIsInstance<AgentEvent.Tool>()
            ?.flatMap { it.attachments }
            ?.forEach { a -> runCatching { File(a.path).delete() } }
        File(dir, "$id.json").delete()
        refresh()
    }
}
