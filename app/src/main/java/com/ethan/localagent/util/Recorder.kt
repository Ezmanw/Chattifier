package com.ethan.localagent.util

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * 16 kHz mono microphone capture with a simple energy-based voice activity detector. Uses the
 * voice-communication source so the phone's echo canceller keeps our own TTS out of the mic.
 */
class Recorder(private val scope: CoroutineScope) {
    /** 0..1 loudness, smoothed, for driving the voice shape. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _utterances = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    /** Complete utterances (PCM16 LE) as detected by the VAD. */
    val utterances: SharedFlow<ByteArray> = _utterances
    private val _speechStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val speechStarted: SharedFlow<Unit> = _speechStarted

    private var job: Job? = null
    private var manual: ByteArrayOutputStream? = null
    @Volatile var vadEnabled = true
    @Volatile private var finishRequested = false
    /** True while the VAD thinks someone is talking. */
    @Volatile var inSpeech = false
        private set

    /** End the current utterance now instead of waiting for silence. */
    fun finishUtterance() { finishRequested = true }

    @SuppressLint("MissingPermission")
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val min = AudioRecord.getMinBufferSize(Media.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, Media.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2,
            )
            val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
            val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
            rec.startRecording()
            val frame = ShortArray(320) // 20 ms
            val bytes = ByteArray(frame.size * 2)
            val utterance = ByteArrayOutputStream()
            val preRoll = ArrayDeque<ByteArray>()
            var noiseFloor = 300.0
            var speaking = false
            var voicedMs = 0
            var silentMs = 0
            try {
                while (isActive) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val s = frame[i].toDouble(); sum += s * s; bytes[2 * i] = (frame[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte() }
                    val rms = sqrt(sum / n)
                    _level.value = (_level.value * 0.6f + (rms / 4000.0).coerceIn(0.0, 1.0).toFloat() * 0.4f)
                    val chunk = bytes.copyOf(n * 2)
                    manual?.write(chunk)
                    if (!vadEnabled) {
                        // Gated (e.g. while the model is speaking): keep the mic open for the echo
                        // canceller but drop any in-progress utterance so we don't capture playback.
                        if (speaking) { speaking = false; inSpeech = false; utterance.reset() }
                        voicedMs = 0; silentMs = 0; preRoll.clear()
                        continue
                    }
                    if (finishRequested) {
                        finishRequested = false
                        if (speaking && utterance.size() > Media.SAMPLE_RATE * 2 / 4) _utterances.tryEmit(utterance.toByteArray())
                        speaking = false; inSpeech = false; voicedMs = 0; utterance.reset(); preRoll.clear()
                        continue
                    }
                    val loud = rms > noiseFloor * 2.8 && rms > 500
                    if (!speaking) {
                        noiseFloor = noiseFloor * 0.98 + rms.coerceAtMost(3000.0) * 0.02
                        preRoll.addLast(chunk); if (preRoll.size > 15) preRoll.removeFirst()
                        voicedMs = if (loud) voicedMs + 20 else 0
                        if (voicedMs >= 160) {
                            speaking = true; inSpeech = true; silentMs = 0
                            utterance.reset(); preRoll.forEach { utterance.write(it) }; preRoll.clear()
                            _speechStarted.tryEmit(Unit)
                        }
                    } else {
                        utterance.write(chunk)
                        silentMs = if (loud) 0 else silentMs + 20
                        if (silentMs >= 900 || utterance.size() > Media.SAMPLE_RATE * 2 * 30) {
                            speaking = false; inSpeech = false; voicedMs = 0
                            if (utterance.size() > Media.SAMPLE_RATE * 2 * 4 / 10) _utterances.tryEmit(utterance.toByteArray())
                            utterance.reset()
                        }
                    }
                }
            } finally {
                rec.stop(); rec.release(); aec?.release(); ns?.release()
                _level.value = 0f
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Push-to-talk style capture for voice notes, independent of the VAD. */
    fun beginManual() { manual = ByteArrayOutputStream(); vadEnabled = false; start() }

    fun endManual(out: File): File? {
        val data = manual?.toByteArray(); manual = null
        stop(); vadEnabled = true
        if (data == null || data.size < Media.SAMPLE_RATE / 5) return null
        Media.writeWav(out, data)
        return out
    }
}
