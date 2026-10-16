package com.ethan.localagent.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ethan.localagent.app
import com.ethan.localagent.data.Attachment
import com.ethan.localagent.data.AttachmentKind
import com.ethan.localagent.data.Chat
import com.ethan.localagent.data.ChatMessage
import com.ethan.localagent.data.Role
import com.ethan.localagent.engine.Turn
import com.ethan.localagent.util.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ChatUiState(
    val chat: Chat = Chat(),
    val generating: Boolean = false,
    val pending: List<Attachment> = emptyList(),
    val preparing: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val c = application.app
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()
    val engineState = c.engine.state
    val summaries = c.chats.summaries
    private var job: Job? = null

    init {
        // Warm the model up while the user is still typing.
        viewModelScope.launch { runCatching { c.engine.ensureLoaded() } }
    }

    fun newChat() {
        stop()
        viewModelScope.launch { c.engine.resetSession(this@ChatViewModel) }
        _ui.value = ChatUiState()
    }

    fun open(id: String) {
        stop()
        viewModelScope.launch {
            val chat = c.chats.load(id) ?: return@launch
            c.engine.resetSession(this@ChatViewModel)
            _ui.value = ChatUiState(chat = chat)
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        c.chats.delete(id)
        if (_ui.value.chat.id == id) newChat()
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun attach(uri: Uri, kind: AttachmentKind) {
        viewModelScope.launch {
            _ui.update { it.copy(preparing = true) }
            try {
                val a = importAttachment(uri, kind)
                _ui.update { it.copy(pending = it.pending + a) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Couldn't attach that file: ${e.message}") }
            } finally {
                _ui.update { it.copy(preparing = false) }
            }
        }
    }

    fun attachRecording(file: File) {
        val a = Attachment(AttachmentKind.AUDIO, file.absolutePath, "Voice note", Media.durationMs(file))
        _ui.update { it.copy(pending = it.pending + a) }
    }

    fun removePending(a: Attachment) = _ui.update { it.copy(pending = it.pending - a) }

    private suspend fun importAttachment(uri: Uri, kind: AttachmentKind): Attachment = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val name = Media.displayName(ctx, uri)
        val id = UUID.randomUUID().toString().take(8)
        val dir = c.chats.attachmentsDir
        when (kind) {
            AttachmentKind.IMAGE -> {
                // Downscale big photos: the vision encoder resizes anyway and this saves a lot of memory.
                val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
                    val max = maxOf(info.size.width, info.size.height)
                    if (max > 1536) d.setTargetSampleSize((max / 1536).coerceAtLeast(1))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val out = File(dir, "$id.jpg")
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Attachment(kind, out.absolutePath, name)
            }
            AttachmentKind.AUDIO -> {
                val raw = Media.copyToFile(ctx, uri, File(ctx.cacheDir, "import_$id"))
                val wav = Media.decodeToWav(raw, File(dir, "$id.wav"))
                raw.delete()
                Attachment(kind, wav.absolutePath, name, Media.durationMs(wav))
            }
            AttachmentKind.VIDEO -> {
                val ext = name.substringAfterLast('.', "mp4")
                val out = Media.copyToFile(ctx, uri, File(dir, "$id.$ext"))
                Attachment(kind, out.absolutePath, name, Media.durationMs(out))
            }
        }
    }

    fun send(text: String) {
        val state = _ui.value
        if (state.generating || (text.isBlank() && state.pending.isEmpty())) return
        val user = ChatMessage(role = Role.USER, text = text.trim(), attachments = state.pending)
        val title = if (state.chat.messages.isEmpty()) titleFrom(text, state.pending) else state.chat.title
        _ui.update {
            it.copy(chat = it.chat.copy(title = title, messages = it.chat.messages + user), pending = emptyList())
        }
        generate(user, history = state.chat.messages, forceNew = false)
    }

    fun regenerate() {
        val msgs = _ui.value.chat.messages
        val lastUser = msgs.indexOfLast { it.role == Role.USER }
        if (lastUser < 0 || _ui.value.generating) return
        _ui.update { it.copy(chat = it.chat.copy(messages = msgs.take(lastUser + 1))) }
        generate(msgs[lastUser], history = msgs.take(lastUser), forceNew = true)
    }

    private fun generate(user: ChatMessage, history: List<ChatMessage>, forceNew: Boolean) {
        val reply = ChatMessage(role = Role.ASSISTANT, text = "")
        _ui.update { it.copy(generating = true, chat = it.chat.copy(messages = it.chat.messages + reply)) }
        job = viewModelScope.launch {
            try {
                val settings = c.prefs.current()
                val session = c.engine.sessionFor(
                    owner = this@ChatViewModel,
                    systemPrompt = settings.systemPrompt,
                    history = history,
                    forceNew = forceNew || !c.engine.owns(this@ChatViewModel),
                )
                session.send(Turn.User(user.text, user.attachments)).collect { chunk ->
                    if (chunk.text.isNotEmpty()) updateReply(reply.id) { it.copy(text = it.text + chunk.text) }
                }
                val (tps, backend) = c.engine.stats()
                updateReply(reply.id) { it.copy(tokensPerSecond = tps, backend = backend?.label) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateReply(reply.id) { it.copy(text = it.text.ifBlank { e.message ?: "Something went wrong" }, error = it.text.isBlank()) }
            } finally {
                _ui.update { it.copy(generating = false, chat = it.chat.copy(updatedAt = System.currentTimeMillis(), modelId = c.engine.current?.id)) }
                c.chats.save(_ui.value.chat)
            }
        }
    }

    fun stop() {
        if (job?.isActive == true) {
            c.engine.cancel()
            job?.cancel()
        }
    }

    private fun updateReply(id: String, f: (ChatMessage) -> ChatMessage) =
        _ui.update { s -> s.copy(chat = s.chat.copy(messages = s.chat.messages.map { if (it.id == id) f(it) else it })) }

    private fun titleFrom(text: String, attachments: List<Attachment>): String {
        val t = text.trim().lineSequence().firstOrNull().orEmpty()
        return when {
            t.isNotEmpty() -> if (t.length > 40) t.take(40).trimEnd() + "…" else t
            attachments.isNotEmpty() -> attachments.first().name
            else -> "New chat"
        }
    }
}
