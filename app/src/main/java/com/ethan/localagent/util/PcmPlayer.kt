package com.ethan.localagent.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs

/** Streams float PCM from a speech model to the speaker as it arrives. */
class PcmPlayer(private val sampleRate: Int = 24_000) {
    private val queue = LinkedBlockingQueue<FloatArray>()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    /** 0..1 loudness of what's playing, for animating the voice shape. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    @Volatile private var track: AudioTrack? = null
    @Volatile private var finishing = false
    private var thread: Thread? = null

    fun write(samples: FloatArray) {
        ensureStarted()
        queue.put(samples)
        _playing.value = true
    }

    /** No more audio is coming for this reply; [playing] drops once the queue drains. */
    fun endOfStream() { finishing = true }

    @Synchronized
    private fun ensureStarted() {
        if (thread?.isAlive == true) { finishing = false; return }
        finishing = false
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, sampleRate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        thread = Thread {
            try {
                while (true) {
                    val chunk = queue.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (finishing) break else continue
                    }
                    var peak = 0f
                    for (s in chunk) peak = maxOf(peak, abs(s))
                    _level.value = peak.coerceIn(0f, 1f)
                    t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                }
                // Let the hardware buffer play out before reporting we're done.
                Thread.sleep(250)
            } catch (_: InterruptedException) {
            } finally {
                runCatching { t.stop(); t.release() }
                if (track === t) track = null
                _level.value = 0f
                _playing.value = false
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        queue.clear()
        thread?.interrupt()
        runCatching { track?.pause(); track?.flush() }
        _playing.value = false
        _level.value = 0f
    }
}
