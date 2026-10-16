package com.ethan.localagent.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class AttachmentKind { IMAGE, AUDIO, VIDEO }

@Serializable
data class Attachment(
    val kind: AttachmentKind,
    /** App-private copy, so the chat survives the original being deleted. */
    val path: String,
    val name: String,
    val durationMs: Long = 0,
)

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val tokensPerSecond: Float? = null,
    val backend: String? = null,
    val error: Boolean = false,
)

@Serializable
data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val modelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
)

data class ChatSummary(val id: String, val title: String, val updatedAt: Long)

private val json = Json { ignoreUnknownKeys = true }

class ChatRepository(context: Context, scope: CoroutineScope) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
    private val _summaries = MutableStateFlow<List<ChatSummary>>(emptyList())
    val summaries: StateFlow<List<ChatSummary>> = _summaries.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    private fun refresh() {
        _summaries.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString<Chat>(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
            .map { ChatSummary(it.id, it.title, it.updatedAt) }
    }

    suspend fun load(id: String): Chat? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<Chat>(File(dir, "$id.json").readText()) }.getOrNull()
    }

    suspend fun save(chat: Chat) = withContext(Dispatchers.IO) {
        if (chat.messages.isEmpty()) return@withContext
        File(dir, "${chat.id}.json").writeText(json.encodeToString(chat))
        _summaries.update { list ->
            (list.filterNot { it.id == chat.id } + ChatSummary(chat.id, chat.title, chat.updatedAt))
                .sortedByDescending { it.updatedAt }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        load(id)?.messages?.flatMap { it.attachments }?.forEach { File(it.path).delete() }
        File(dir, "$id.json").delete()
        _summaries.update { list -> list.filterNot { it.id == id } }
    }
}
