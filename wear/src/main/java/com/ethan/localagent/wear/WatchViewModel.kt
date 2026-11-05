package com.ethan.localagent.wear

import android.app.Application
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class WatchScreen { Home, Thinking, Reply, Offline }

data class WatchUi(
    val screen: WatchScreen = WatchScreen.Home,
    /** null while checking; the model name once the phone answered. */
    val phoneModel: String? = null,
    val phoneReachable: Boolean = false,
    val phoneEnabled: Boolean = true,
    val question: String = "",
    val reply: String = "",
    val error: Boolean = false,
    val seconds: Float? = null,
    val speaking: Boolean = false,
)

/**
 * The watch side of a question: send it to the phone, show the streamed answer, and speak it
 * (phone-rendered Kokoro audio when it comes, otherwise the watch's own TTS).
 */
class WatchViewModel(app: Application) : AndroidViewModel(app) {
    private val link = PhoneLink(app)
    private val _ui = MutableStateFlow(WatchUi())
    val ui: StateFlow<WatchUi> = _ui.asStateFlow()

    private var currentId: String? = null
    private var askedAt = 0L
    private var askedByVoice = false
    private var inConversation = false
    private var waiting: Job? = null
    private var retrying: Job? = null
    @Volatile private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        refresh()
        viewModelScope.launch { link.replies().collect(::onReply) }
        viewModelScope.launch {
            link.audio().collect { (id, channel) ->
                if (id != currentId) return@collect
                _ui.update { it.copy(speaking = true) }
                runCatching { link.play(channel) { track = it } }
                track = null
                _ui.update { it.copy(speaking = false) }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        val s = link.status()
        _ui.update { it.copy(phoneReachable = s != null, phoneModel = s?.model ?: "", phoneEnabled = s?.enabled ?: true) }
    }

    fun ask(text: String, byVoice: Boolean, followUp: Boolean = false) {
        if (text.isBlank()) return
        stopSpeaking()
        val id = UUID.randomUUID().toString()
        currentId = id
        askedByVoice = byVoice
        _ui.update { it.copy(screen = WatchScreen.Thinking, question = text, reply = "", error = false, seconds = null) }
        waiting?.cancel()
        waiting = viewModelScope.launch {
            val node = link.status()?.node
            if (node == null) {
                _ui.update { it.copy(screen = WatchScreen.Offline, phoneReachable = false) }
                retryWhenBack()
                return@launch
            }
            _ui.update { it.copy(phoneReachable = true) }
            askedAt = System.currentTimeMillis()
            runCatching { link.ask(node, id, text, byVoice, followUp && inConversation) }.onFailure {
                _ui.update { it.copy(screen = WatchScreen.Offline, phoneReachable = false) }
                retryWhenBack()
                return@launch
            }
            delay(REPLY_TIMEOUT_MS)
            if (currentId == id && _ui.value.screen == WatchScreen.Thinking) {
                _ui.update { it.copy(screen = WatchScreen.Reply, reply = "Your phone didn't answer in time.", error = true) }
            }
        }
    }

    /** The saved question goes out by itself once the phone is back. */
    private fun retryWhenBack() {
        retrying?.cancel()
        retrying = viewModelScope.launch {
            while (_ui.value.screen == WatchScreen.Offline) {
                delay(RETRY_EVERY_MS)
                if (link.status() != null) { retryNow(); break }
            }
        }
    }

    fun retryNow() {
        val q = _ui.value.question
        if (q.isNotBlank()) ask(q, askedByVoice) else { _ui.update { it.copy(screen = WatchScreen.Home) }; refresh() }
    }

    private fun onReply(r: Reply) {
        if (r.id != currentId) return
        if (!r.done) {
            _ui.update { it.copy(screen = WatchScreen.Reply, reply = r.text) }
            return
        }
        waiting?.cancel()
        inConversation = !r.error
        _ui.update {
            it.copy(
                screen = WatchScreen.Reply, reply = r.text, error = r.error,
                seconds = (System.currentTimeMillis() - askedAt) / 1000f,
                phoneModel = r.model.ifBlank { it.phoneModel },
            )
        }
        if (r.speak && !r.phoneAudio && !r.error) speakLocally(r.text)
    }

    private fun speakLocally(text: String) {
        val engine = tts
        if (engine == null) {
            // First use: speak once the engine is up.
            tts = TextToSpeech(getApplication()) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) speakLocally(text)
            }
            return
        }
        if (!ttsReady) return
        _ui.update { it.copy(speaking = true) }
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { _ui.update { it.copy(speaking = false) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { _ui.update { it.copy(speaking = false) } }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, currentId)
    }

    fun stopSpeaking() {
        track?.runCatching { pause(); flush() }
        tts?.stop()
        _ui.update { it.copy(speaking = false) }
    }

    fun home() {
        stopSpeaking()
        waiting?.cancel(); retrying?.cancel()
        currentId = null
        _ui.update { it.copy(screen = WatchScreen.Home) }
        refresh()
    }

    override fun onCleared() {
        stopSpeaking()
        tts?.shutdown()
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 180_000L
        const val RETRY_EVERY_MS = 10_000L
    }
}
