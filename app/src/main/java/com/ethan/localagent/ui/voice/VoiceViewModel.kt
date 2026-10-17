package com.ethan.localagent.ui.voice

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ethan.localagent.app
import com.ethan.localagent.data.Attachment
import com.ethan.localagent.data.AttachmentKind
import com.ethan.localagent.data.ChatMessage
import com.ethan.localagent.data.Role
import com.ethan.localagent.engine.Turn
import com.ethan.localagent.util.PcmPlayer
import com.ethan.localagent.util.Media
import com.ethan.localagent.util.Recorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class VoicePhase { Idle, Listening, Paused, Thinking, Speaking }

data class VoiceUi(
    val phase: VoicePhase = VoicePhase.Idle,
    val heard: String = "",
    val reply: String = "",
    val muted: Boolean = false,
    val captions: Boolean = false,
    val transcript: List<ChatMessage> = emptyList(),
    val error: String? = null,
    val usesAudioModel: Boolean = false,
    /** The model answers in its own voice (speech-to-speech) instead of through TTS. */
    val ownVoice: Boolean = false,
)

/**
 * Hands-free conversation. Models that hear audio get the microphone directly; text-only models
 * get on-device speech recognition in front of them. Replies are spoken sentence by sentence and
 * you can talk over them to interrupt.
 */
class VoiceViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.app
    private val recorder = Recorder(viewModelScope)
    private val player = PcmPlayer()
    val level: StateFlow<Float> = recorder.level
    /** Loudness of whatever is speaking (the model's own voice or Kokoro). */
    val outputLevel: StateFlow<Float> = kotlinx.coroutines.flow.combine(player.level, c.speaker.level) { a, b -> maxOf(a, b) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0f)
    val voicePack = c.speaker.pack
    fun downloadVoices() = c.speaker.downloadPack()
    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui.asStateFlow()
    val voices = c.speaker.voices
    private var job: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var turnStartedAt = 0L

    init {
        viewModelScope.launch {
            recorder.utterances.collect { pcm -> if (_ui.value.usesAudioModel) onUtterance(pcm) }
        }
        viewModelScope.launch {
            recorder.speechStarted.collect {
                val s = _ui.value
                val talkOver = c.prefs.current().talkOver
                // Ignore the first moments of our own speech in case echo cancellation lets some through.
                if (talkOver && (s.phase == VoicePhase.Speaking || s.phase == VoicePhase.Thinking) &&
                    System.currentTimeMillis() - turnStartedAt > 600
                ) interrupt()
            }
        }
        viewModelScope.launch {
            c.speaker.speaking.collect { speaking ->
                if (!speaking && !_ui.value.ownVoice && _ui.value.phase == VoicePhase.Speaking && job?.isActive != true) listen()
            }
        }
        viewModelScope.launch {
            player.playing.collect { playing ->
                if (!playing && _ui.value.ownVoice && _ui.value.phase == VoicePhase.Speaking && job?.isActive != true) listen()
            }
        }
    }

    fun start() {
        viewModelScope.launch {
            val s = c.prefs.current()
            c.speaker.setVoice(s.voiceName, s.speechRate)
            val model = runCatching { c.engine.ensureLoaded() }.getOrElse {
                _ui.update { u -> u.copy(error = it.message) }; return@launch
            }.model
            _ui.update { it.copy(usesAudioModel = model.audio, ownVoice = model.speaksForItself, error = null) }
            listen()
        }
    }

    fun stopAll() {
        job?.cancel(); c.engine.cancel(); c.speaker.stop(); player.stop()
        recorder.stop(); recognizer?.destroy(); recognizer = null
        _ui.update { it.copy(phase = VoicePhase.Idle) }
    }

    fun toggleMute() {
        val muted = !_ui.value.muted
        _ui.update { it.copy(muted = muted) }
        if (muted) { recorder.stop(); recognizer?.cancel() } else if (_ui.value.phase == VoicePhase.Listening) listen()
    }

    fun toggleCaptions() = _ui.update { it.copy(captions = !it.captions) }

    fun setVoice(id: String) = viewModelScope.launch {
        c.prefs.update { it.copy(voiceName = id) }
        val s = c.prefs.current(); c.speaker.setVoice(id, s.speechRate); c.speaker.speak("Hi, this is how I sound.")
    }

    fun setRate(r: Float) = viewModelScope.launch {
        c.prefs.update { it.copy(speechRate = r) }
        c.speaker.setVoice(c.prefs.current().voiceName, r)
    }

    fun setTalkOver(on: Boolean) = viewModelScope.launch { c.prefs.update { it.copy(talkOver = on) } }

    /**
     * Tap on the shape. While listening it stops listening: whatever you've said so far is sent
     * straight away, or, if you haven't started talking, listening pauses until the next tap.
     * While the AI is thinking or talking, a tap interrupts it.
     */
    fun tapShape() {
        when (_ui.value.phase) {
            VoicePhase.Speaking, VoicePhase.Thinking -> interrupt()
            VoicePhase.Listening -> {
                if (_ui.value.usesAudioModel) {
                    if (recorder.inSpeech) recorder.finishUtterance() else pause()
                } else {
                    if (_ui.value.heard.isNotBlank()) recognizer?.stopListening() else pause()
                }
            }
            VoicePhase.Paused, VoicePhase.Idle -> listen()
        }
    }

    private fun pause() {
        recorder.stop(); recognizer?.cancel()
        _ui.update { it.copy(phase = VoicePhase.Paused) }
    }

    private fun interrupt() {
        job?.cancel(); c.engine.cancel(); c.speaker.stop(); player.stop()
        listen()
    }

    private fun listen() {
        _ui.update { it.copy(phase = VoicePhase.Listening, heard = "") }
        if (_ui.value.muted) return
        if (_ui.value.usesAudioModel) { recorder.vadEnabled = true; recorder.start() } else startRecognizer()
    }

    private fun startRecognizer() {
        val ctx = getApplication<Application>()
        val r = recognizer ?: (if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { t -> _ui.update { it.copy(heard = t) } }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isBlank()) listen() else onText(t)
            }
            override fun onError(error: Int) { if (_ui.value.phase == VoicePhase.Listening) viewModelScope.launch { kotlinx.coroutines.delay(300); listen() } }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    private fun onUtterance(pcm: ByteArray) {
        val ctx = getApplication<Application>()
        val wav = File(ctx.cacheDir, "voice/turn_${System.currentTimeMillis()}.wav")
        Media.writeWav(wav, pcm)
        val seconds = pcm.size / 2f / Media.SAMPLE_RATE
        _ui.update { it.copy(heard = "%.1f s of speech".format(seconds)) }
        respond(ChatMessage(role = Role.USER, text = "", attachments = listOf(Attachment(AttachmentKind.AUDIO, wav.absolutePath, "speech"))))
    }

    private fun onText(text: String) {
        _ui.update { it.copy(heard = text) }
        respond(ChatMessage(role = Role.USER, text = text))
    }

    private fun respond(user: ChatMessage) {
        job?.cancel()
        turnStartedAt = System.currentTimeMillis()
        // Set the phase first so the player-stopped observer below doesn't mistake this for the end
        // of a reply and restart listening.
        _ui.update { it.copy(phase = VoicePhase.Thinking, reply = "") }
        // Gate the mic (keep it open, but drop input) and clear any leftover audio while we think
        // and speak, so the model's own voice from the speaker isn't fed back as a new turn.
        // Gating rather than stopping avoids churning the AudioRecord, which fails to re-init.
        recorder.vadEnabled = false; recognizer?.cancel(); player.stop()
        job = viewModelScope.launch {
            val history = _ui.value.transcript
            val ownVoice = _ui.value.ownVoice
            try {
                val session = c.engine.sessionFor(this@VoiceViewModel, VOICE_PROMPT, history, forceNew = !c.engine.owns(this@VoiceViewModel))
                val text = if (user.attachments.isNotEmpty() && !ownVoice) "(The user just spoke. Reply to what they said.)" else user.text
                var reply = ""
                session.send(Turn.User(text, user.attachments)).collect { chunk ->
                    if (chunk.text.isEmpty() && chunk.audio == null) return@collect
                    if (_ui.value.phase == VoicePhase.Thinking) _ui.update { it.copy(phase = VoicePhase.Speaking) }
                    chunk.audio?.let { player.write(it) }
                    if (chunk.text.isNotEmpty()) {
                        reply += chunk.text
                        _ui.update { it.copy(reply = reply) }
                        if (!ownVoice) c.speaker.feed(chunk.text)
                    }
                }
                if (ownVoice) player.endOfStream() else c.speaker.flushFeed()
                val heard = _ui.value.heard
                _ui.update {
                    it.copy(transcript = it.transcript + user.copy(text = user.text.ifBlank { "🎙 $heard" }, attachments = emptyList()) +
                        ChatMessage(role = Role.ASSISTANT, text = reply))
                }
                val stillTalking = if (ownVoice) player.playing.value else c.speaker.speaking.value
                if (!stillTalking) listen()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
                listen()
            }
        }
    }

    override fun onCleared() {
        stopAll()
        viewModelScope.launch { c.engine.resetSession(this@VoiceViewModel) }
    }

    companion object {
        private const val VOICE_PROMPT =
            "You are talking out loud with the user through their phone's speaker. Reply in a natural, conversational " +
                "way — usually one to three sentences, a little more only when the question really needs it. " +
                "No lists, no markdown, no emoji."
    }
}
