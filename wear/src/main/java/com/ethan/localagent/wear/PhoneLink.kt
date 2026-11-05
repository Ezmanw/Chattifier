package com.ethan.localagent.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.DataInputStream

/** Message paths shared with the phone app (app/…/wear/WearRequestService.kt keeps the same values). */
object Paths {
    const val PING = "/chattifier/ping"
    const val STATUS = "/chattifier/status"
    const val ASK = "/chattifier/ask"
    const val REPLY = "/chattifier/reply"
    const val AUDIO = "/chattifier/audio/"
}

data class PhoneStatus(val node: Node, val enabled: Boolean, val model: String)

/** [speak]: the phone wants this said aloud; [phoneAudio]: and it's sending the audio itself. */
data class Reply(
    val id: String,
    val text: String,
    val done: Boolean,
    val error: Boolean,
    val model: String,
    val speak: Boolean,
    val phoneAudio: Boolean,
)

/** Talks to Chattifier on the paired phone over the Wear OS Data Layer (Bluetooth or local Wi‑Fi). */
class PhoneLink(context: Context) {
    private val ctx = context.applicationContext
    private val messages = Wearable.getMessageClient(ctx)
    private val channels = Wearable.getChannelClient(ctx)

    /** Pings the phone and waits for Chattifier to answer. Null when it's out of reach or not installed. */
    suspend fun status(timeoutMs: Long = 4_000): PhoneStatus? {
        val node = nearestPhone() ?: return null
        val answer = CompletableDeferred<JSONObject>()
        val listener = MessageClient.OnMessageReceivedListener { e ->
            if (e.path == Paths.STATUS && e.sourceNodeId == node.id) answer.complete(JSONObject(String(e.data)))
        }
        messages.addListener(listener).await()
        try {
            runCatching { messages.sendMessage(node.id, Paths.PING, ByteArray(0)).await() }.getOrElse { return null }
            val json = withTimeoutOrNull(timeoutMs) { answer.await() } ?: return null
            return PhoneStatus(node, json.optBoolean("enabled", true), json.optString("model"))
        } finally {
            messages.removeListener(listener)
        }
    }

    suspend fun nearestPhone(): Node? = runCatching {
        val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
        nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }.getOrNull()

    suspend fun ask(node: Node, id: String, text: String, voice: Boolean, followUp: Boolean) {
        val json = JSONObject().put("id", id).put("text", text).put("voice", voice).put("followUp", followUp)
        messages.sendMessage(node.id, Paths.ASK, json.toString().toByteArray()).await()
    }

    /** Streamed reply updates from the phone, partial text first and then one with done = true. */
    fun replies(): Flow<Reply> = callbackFlow {
        val listener = MessageClient.OnMessageReceivedListener { e: MessageEvent ->
            if (e.path != Paths.REPLY) return@OnMessageReceivedListener
            val j = JSONObject(String(e.data))
            trySend(Reply(j.getString("id"), j.optString("text"), j.optBoolean("done"), j.optBoolean("error"), j.optString("model"), j.optBoolean("speak"), j.optBoolean("phoneAudio")))
        }
        messages.addListener(listener)
        awaitClose { messages.removeListener(listener) }
    }

    /** Spoken replies rendered on the phone (Kokoro), keyed by request id. */
    fun audio(): Flow<Pair<String, ChannelClient.Channel>> = callbackFlow {
        val callback = object : ChannelClient.ChannelCallback() {
            override fun onChannelOpened(channel: ChannelClient.Channel) {
                if (channel.path.startsWith(Paths.AUDIO)) trySend(channel.path.removePrefix(Paths.AUDIO) to channel)
            }
        }
        channels.registerChannelCallback(callback)
        awaitClose { channels.unregisterChannelCallback(callback) }
    }

    /** Plays one phone-rendered reply: an int sample rate, then 16-bit little-endian mono PCM. */
    suspend fun play(channel: ChannelClient.Channel, onTrack: (AudioTrack) -> Unit) = withContext(Dispatchers.IO) {
        channels.getInputStream(channel).await().use { raw ->
            val input = DataInputStream(raw.buffered(1 shl 15))
            val rate = input.readInt()
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            onTrack(track)
            try {
                track.play()
                val buf = ByteArray(8_192)
                var frames = 0L
                while (true) {
                    val n = input.read(buf)
                    // Paused from outside = the user tapped stop.
                    if (n < 0 || track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                    track.write(buf, 0, n)
                    frames += n / 2
                }
                // Let the tail drain before releasing; the head keeps moving after stop() in stream mode.
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                    val deadline = System.currentTimeMillis() + 5_000
                    while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < frames &&System.currentTimeMillis() < deadline) Thread.sleep(20)
                }
            } finally {
                track.release()
            }
        }
    }
}
