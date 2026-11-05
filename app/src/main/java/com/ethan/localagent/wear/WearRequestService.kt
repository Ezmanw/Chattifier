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
        // The watch keeps the conversation (and its history list), so it sends the earlier turns.
        val history = req.optJSONArray("history")?.let { arr ->
            (0 until arr.length()).flatMap { i ->
                val t = arr.getJSONObject(i)
                listOf(ChatMessage(role = Role.USER, text = t.optString("q")), ChatMessage(role = Role.ASSISTANT, text = t.optString("a")))
            }
        }.orEmpty().takeLast(MAX_HISTORY_MESSAGES)

        val out = StringBuilder()
        try {
            val prompt = if (s.wearShort) "$WATCH_PROMPT $SHORT" else WATCH_PROMPT
            val session = c.engine.sessionFor(WatchSession, prompt, history, forceNew = true)
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
        val modelName = c.engine.current?.name
        val wantVoice = req.optBoolean("voice") && s.wearReply != WearReply.TEXT
        // The final text goes out first; rendering the voice takes seconds and mustn't hold it up.
        val phoneVoice = wantVoice && c.speaker.rendersNeural
        send(node, WearPaths.REPLY, reply(answer, done = true, model = modelName, speak = wantVoice, phoneAudio = phoneVoice))
        if (phoneVoice && !sendAudio(node, id, answer)) {
            // Kokoro failed after all: a second "done" without phone audio makes the watch speak it.
            send(node, WearPaths.REPLY, reply(answer, done = true, model = modelName, speak = true, phoneAudio = false))
        }
    }

    private suspend fun send(node: String, path: String, json: JSONObject) {
        runCatching { Wearable.getMessageClient(this).sendMessage(node, path, json.toString().toByteArray()).await() }
            .onFailure { Log.w(TAG, "couldn't reach watch on $path", it) }
    }

    /**
     * Renders [text] with Kokoro a sentence at a time and streams it to the watch as 16-bit PCM:
     * an int sample rate, then little-endian samples. The watch starts playing after the first
     * sentence instead of waiting for the whole reply. False if nothing could be rendered or sent.
     */
    private suspend fun sendAudio(node: String, id: String, text: String): Boolean {
        val channels = Wearable.getChannelClient(this)
        var out: DataOutputStream? = null
        return runCatching {
            try {
                for (sentence in text.split(SENTENCE_BREAK).filter { it.isNotBlank() }) {
                    val audio = app.speaker.synthesize(sentence) ?: continue
                    val stream = out ?: DataOutputStream(
                        channels.getOutputStream(channels.openChannel(node, WearPaths.AUDIO + id).await()).await().buffered(1 shl 15),
                    ).also { it.writeInt(audio.sampleRate); out = it }
                    val bytes = ByteArray(audio.samples.size * 2)
                    audio.samples.forEachIndexed { i, f ->
                        val v = (f.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                        bytes[i * 2] = (v and 0xff).toByte()
                        bytes[i * 2 + 1] = (v shr 8 and 0xff).toByte()
                    }
                    stream.write(bytes)
                    stream.flush()
                }
            } finally {
                out?.close()
            }
            out != null
        }.onFailure { Log.w(TAG, "couldn't stream audio to watch", it) }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "WearRequestService"
        private const val ASK_TIMEOUT_MS = 180_000L
        private const val MAX_HISTORY_MESSAGES = 12
        // Each update re-lays-out the whole reply on the watch; faster than this makes it stutter.
        private const val STREAM_EVERY_MS = 700L
        private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+|\\n+")
        private const val WATCH_PROMPT =
            "You are answering a question asked on the user's smartwatch. The reply is read on a small round " +
                "screen or spoken aloud, so use plain sentences with no markdown, lists or code blocks."
        private const val SHORT = "Keep it to two or three short sentences."
    }
}

/** Owner of the engine session while answering the watch. */
object WatchSession
