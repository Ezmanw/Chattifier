package com.ethan.localagent.util

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** A voice to pick in the menu. Ids starting "kokoro:" are neural voices. */
data class VoiceOption(val id: String, val label: String, val hint: String)

sealed interface VoicePackState {
    data object Missing : VoicePackState
    data class Downloading(val progress: Float) : VoicePackState
    data object Ready : VoicePackState
    data class Failed(val message: String) : VoicePackState
}

/**
 * Text-to-speech. Uses Kokoro-82M (a small neural TTS model, offline, via sherpa-onnx) once its
 * voice pack is downloaded, and the phone's own TTS before that. Replies are fed in sentence by
 * sentence while the model is still writing, so speech starts almost straight away.
 */
class Speaker(private val context: Context, private val http: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val packDir = File(context.filesDir, "kokoro")
    private val modelDir = File(packDir, KOKORO_DIR)

    private val _pack = MutableStateFlow<VoicePackState>(if (File(modelDir, "model.int8.onnx").exists()) VoicePackState.Ready else VoicePackState.Missing)
    val pack: StateFlow<VoicePackState> = _pack

    // --- system TTS ---
    private val pending = AtomicInteger(0)
    private val systemSpeaking = MutableStateFlow(false)
    private val systemVoices = MutableStateFlow<List<VoiceOption>>(emptyList())
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) systemVoices.value = listSystemVoices()
    }

    // --- Kokoro ---
    private val player = PcmPlayer(24_000)
    @Volatile private var kokoro: OfflineTts? = null
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var generation = 0
    private val kokoroBusy = MutableStateFlow(false)

    private var voiceId: String? = null
    private var rate = 1.0f
    private var buffer = StringBuilder()

    val speaking: StateFlow<Boolean> = combine(systemSpeaking, player.playing, kokoroBusy) { a, b, c -> a || b || c }
        .stateIn(scope, SharingStarted.Eagerly, false)
    /** Loudness of neural speech, for animating the voice shape. */
    val level: StateFlow<Float> = player.level

    val voices: StateFlow<List<VoiceOption>> = combine(systemVoices, _pack) { sys, p ->
        if (p == VoicePackState.Ready) KOKORO_VOICES + sys else sys
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { systemSpeaking.value = true }
            override fun onDone(id: String?) { if (pending.decrementAndGet() <= 0) { pending.set(0); systemSpeaking.value = false } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { onDone(id) }
            override fun onStop(id: String?, interrupted: Boolean) { pending.set(0); systemSpeaking.value = false }
        })
        Thread(::kokoroWorker, "kokoro").apply { isDaemon = true }.start()
    }

    private val useKokoro: Boolean get() = _pack.value == VoicePackState.Ready && (voiceId == null || voiceId!!.startsWith("kokoro:"))

    private fun listSystemVoices(): List<VoiceOption> {
        val lang = Locale.getDefault().language
        val all = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        return all.filter { !it.isNetworkConnectionRequired && it.locale.language == lang }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == Locale.getDefault() }.thenByDescending { it.quality })
            .take(4)
            .mapIndexed { i, v -> VoiceOption(v.name, "System voice ${i + 1}", v.locale.displayCountry.ifBlank { v.locale.displayLanguage }) }
    }

    fun setVoice(id: String?, speed: Float) {
        voiceId = id
        rate = speed
        tts.setSpeechRate(speed)
        if (id != null && !id.startsWith("kokoro:")) tts.voices?.firstOrNull { it.name == id }?.let { tts.voice = it }
    }

    fun speak(text: String, flush: Boolean = true) {
        val clean = text.replace(MARKDOWN, "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        if (flush) stopPlayback()
        if (useKokoro) {
            kokoroBusy.value = true
            queue.put(clean)
        } else {
            pending.incrementAndGet()
            systemSpeaking.value = true
            tts.speak(clean, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), UUID.randomUUID().toString())
        }
    }

    /** Feed streamed text; complete sentences are spoken as soon as they end. */
    fun feed(delta: String) {
        buffer.append(delta)
        while (true) {
            val m = SENTENCE_END.find(buffer) ?: break
            val sentence = buffer.substring(0, m.range.last + 1)
            buffer.delete(0, m.range.last + 1)
            speak(sentence, flush = false)
        }
    }

    fun flushFeed() {
        val rest = buffer.toString(); buffer = StringBuilder()
        speak(rest, flush = false)
    }

    fun stop() {
        buffer = StringBuilder()
        stopPlayback()
    }

    private fun stopPlayback() {
        generation++
        queue.clear()
        player.stop()
        kokoroBusy.value = false
        pending.set(0)
        tts.stop()
        systemSpeaking.value = false
    }

    private fun kokoroWorker() {
        while (true) {
            val text = queue.take()
            val gen = generation
            try {
                val engine = kokoro ?: loadKokoro() ?: continue
                val sid = KOKORO_VOICES.indexOfFirst { it.id == voiceId }.let { if (it < 0) 0 else KOKORO_IDS[it] }
                // generate() (not generateWithCallback) avoids a JNI callback-signature mismatch in
                // sherpa-onnx that aborts the process. Kokoro sentences are short, so a per-sentence
                // wait is fine — the worker still streams sentence by sentence from the queue.
                val audio = engine.generate(text, sid, rate)
                if (gen == generation) player.write(audio.samples)
            } catch (t: Throwable) {
                Log.w(TAG, "kokoro failed", t)
            } finally {
                if (queue.isEmpty() && gen == generation) {
                    player.endOfStream()
                    kokoroBusy.value = false
                }
            }
        }
    }

    private fun loadKokoro(): OfflineTts? = runCatching {
        val d = modelDir.absolutePath
        OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$d/model.int8.onnx",
                        voices = "$d/voices.bin",
                        tokens = "$d/tokens.txt",
                        dataDir = "$d/espeak-ng-data",
                        lexicon = "$d/lexicon-us-en.txt,$d/lexicon-zh.txt",
                        lang = "en-us",
                    ),
                    numThreads = 4,
                ),
            ),
        ).also { kokoro = it }
    }.onFailure { Log.w(TAG, "couldn't load Kokoro", it) }.getOrNull()

    /** Downloads and unpacks the Kokoro voice pack (~130 MB). */
    fun downloadPack() {
        if (_pack.value is VoicePackState.Downloading || _pack.value == VoicePackState.Ready) return
        scope.launch(Dispatchers.IO) {
            try {
                packDir.mkdirs()
                val archive = File(packDir, "pack.tar.bz2")
                _pack.value = VoicePackState.Downloading(0f)
                http.newCall(Request.Builder().url(KOKORO_URL).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val total = r.body.contentLength()
                    var done = 0L
                    FileOutputStream(archive).use { out ->
                        r.body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (total > 0) _pack.value = VoicePackState.Downloading(done.toFloat() / total * 0.85f)
                            }
                        }
                    }
                }
                _pack.value = VoicePackState.Downloading(0.9f)
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        if (e.name.split('/').any { it == ".." }) continue
                        val f = File(packDir, e.name)
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { tar.copyTo(it) } }
                    }
                }
                archive.delete()
                _pack.value = VoicePackState.Ready
            } catch (e: Exception) {
                Log.w(TAG, "voice pack download failed", e)
                _pack.value = VoicePackState.Failed(e.message ?: "Download failed")
            }
        }
    }

    companion object {
        private const val TAG = "Speaker"
        private const val KOKORO_DIR = "kokoro-int8-multi-lang-v1_0"
        private const val KOKORO_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$KOKORO_DIR.tar.bz2"
        const val KOKORO_PACK_BYTES = 132_303_094L

        /** Speaker order inside voices.bin (from the model's speaker_names metadata). */
        private val ALL = "af_alloy,af_aoede,af_bella,af_heart,af_jessica,af_kore,af_nicole,af_nova,af_river,af_sarah,af_sky,am_adam,am_echo,am_eric,am_fenrir,am_liam,am_michael,am_onyx,am_puck,am_santa,bf_alice,bf_emma,bf_isabella,bf_lily,bm_daniel,bm_fable,bm_george,bm_lewis".split(',')

        /** The English voices worth offering, best first. */
        private val PICKS = listOf(
            "af_heart" to "Heart", "af_bella" to "Bella", "af_nicole" to "Nicole", "af_aoede" to "Aoede",
            "am_michael" to "Michael", "am_fenrir" to "Fenrir", "am_puck" to "Puck",
            "bf_emma" to "Emma", "bf_isabella" to "Isabella", "bm_george" to "George", "bm_fable" to "Fable",
        )
        val KOKORO_VOICES: List<VoiceOption> = PICKS.map { (id, name) ->
            val accent = if (id.startsWith("b")) "UK" else "US"
            val who = if (id[1] == 'f') "♀" else "♂"
            VoiceOption("kokoro:$id", name, "$accent $who")
        }
        private val KOKORO_IDS: List<Int> = PICKS.map { ALL.indexOf(it.first) }

        private val SENTENCE_END = Regex("[.!?。！？](\\s|$)|\\n")
        private val MARKDOWN = Regex("[*_`#>]+")
    }
}
