package com.ethan.localagent.wear

import android.util.Log
import com.ethan.localagent.app
import com.ethan.localagent.data.ChatMessage
import com.ethan.localagent.data.Role
import com.ethan.localagent.data.WearReply
import com.ethan.localagent.engine.EngineState
import com.ethan.localagent.engine.Turn
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.DataOutputStream

/**
 * Message paths shared with the watch app (wear/…/Protocol.kt keeps the same values). Everything
 * goes over the Wear OS Data Layer, which runs on Bluetooth or local Wi‑Fi, so a watch question is
 * answered with no internet at all.
 */
object WearPaths {
    const val PING = "/chattifier/ping"
    const val STATUS = "/chattifier/status"
    const val ASK = "/chattifier/ask"
    const val REPLY = "/chattifier/reply"
    const val AUDIO = "/chattifier/audio/"
}

/**
 * Answers questions asked on the watch with the phone's loaded model. Woken by the system when a
 * watch message arrives, even with the app closed.
 */
class WearRequestService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.PING -> runBlocking { sendStatus(event.sourceNodeId) }
            // onMessageReceived runs on a binder worker, not the main thread, and blocking here keeps
            // the service alive until the reply is out.
            WearPaths.ASK -> runBlocking {
                runCatching { withTimeout(ASK_TIMEOUT_MS) { answer(event.sourceNodeId, JSONObject(String(event.data))) } }
                    .onFailure { Log.w(TAG, "watch request failed", it) }
            }
        }
    }

    private suspend fun sendStatus(node: String) {
        val c = app
        val s = c.prefs.current()
        val model = c.models.byId(s.activeModelId) ?: c.models.models.value.firstOrNull()
        val json = JSONObject()
            .put("enabled", s.wearEnabled)
            .put("model", model?.name ?: "")
            .put("loaded", c.engine.state.value is EngineState.Ready)
        send(node, WearPaths.STATUS, json)
    }

    private suspend fun answer(node: String, req: JSONObject) {
        val c = app
        val id = req.getString("id")
        val text = req.optString("text").trim()
        val s = c.prefs.current()
        fun reply(text: String, done: Boolean, error: Boolean = false, model: String? = null, speak: Boolean = false, phoneAudio: Boolean = false) =
            JSONObject().put("id", id).put("text", text).put("done", done).put("error", error)
                .put("model", model ?: "").put("speak", speak).put("phoneAudio", phoneAudio)

        if (!s.wearEnabled) {
            send(node, WearPaths.REPLY, reply("Watch answers are turned off in Chattifier on your phone.", done = true, error = true))
            return
        }
        if (text.isEmpty()) return
        if (!req.optBoolean("followUp")) WatchConversation.clear()

        val out = StringBuilder()
        try {
            val prompt = if (s.wearShort) "$WATCH_PROMPT $SHORT" else WATCH_PROMPT
            val session = c.engine.sessionFor(WatchConversation, prompt, WatchConversation.history, forceNew = true)
            var lastSent = 0L
            session.send(Turn.User(text)).collect { chunk ->
                if (chunk.text.isEmpty()) return@collect
                out.append(chunk.text)
                val now = System.currentTimeMillis()
                if (now - lastSent > STREAM_EVERY_MS) {
                    lastSent = now
                    send(node, WearPaths.REPLY, reply(out.toString(), done = false))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "model failed for watch", t)
            send(node, WearPaths.REPLY, reply(t.message ?: "The model couldn't answer.", done = true, error = true))
            return
        }

        val answer = out.toString().trim()
        WatchConversation.add(ChatMessage(role = Role.USER, text = text), ChatMessage(role = Role.ASSISTANT, text = answer))
        val modelName = c.engine.current?.name
        val wantVoice = req.optBoolean("voice") && s.wearReply != WearReply.TEXT
        val audio = if (wantVoice) c.speaker.synthesize(answer) else null
        send(node, WearPaths.REPLY, reply(answer, done = true, model = modelName, speak = wantVoice, phoneAudio = audio != null))
        if (audio != null) sendAudio(node, id, audio.samples, audio.sampleRate)
    }

    private suspend fun send(node: String, path: String, json: JSONObject) {
        runCatching { Wearable.getMessageClient(this).sendMessage(node, path, json.toString().toByteArray()).await() }
            .onFailure { Log.w(TAG, "couldn't reach watch on $path", it) }
    }

    /** Streams 16-bit PCM to the watch: an int sample rate, then little-endian samples. */
    private suspend fun sendAudio(node: String, id: String, samples: FloatArray, sampleRate: Int) {
        val channels = Wearable.getChannelClient(this)
        runCatching {
            val channel = channels.openChannel(node, WearPaths.AUDIO + id).await()
            channels.getOutputStream(channel).await().use { raw ->
                val out = DataOutputStream(raw.buffered(1 shl 15))
                out.writeInt(sampleRate)
                val bytes = ByteArray(samples.size * 2)
                samples.forEachIndexed { i, f ->
                    val v = (f.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    bytes[i * 2] = (v and 0xff).toByte()
                    bytes[i * 2 + 1] = (v shr 8 and 0xff).toByte()
                }
                out.write(bytes)
                out.flush()
            }
        }.onFailure { Log.w(TAG, "couldn't stream audio to watch", it) }
    }

    companion object {
        private const val TAG = "WearRequestService"
        private const val ASK_TIMEOUT_MS = 180_000L
        private const val STREAM_EVERY_MS = 400L
        private const val WATCH_PROMPT =
            "You are answering a question asked on the user's smartwatch. The reply is read on a small round " +
                "screen or spoken aloud, so use plain sentences with no markdown, lists or code blocks."
        private const val SHORT = "Keep it to two or three short sentences."
    }
}

/** The watch's running conversation, so follow-ups have context. Also the engine session owner. */
object WatchConversation {
    private const val MAX_MESSAGES = 12
    @Volatile var history: List<ChatMessage> = emptyList()
        private set

    fun add(vararg messages: ChatMessage) { history = (history + messages).takeLast(MAX_MESSAGES) }
    fun clear() { history = emptyList() }
}
