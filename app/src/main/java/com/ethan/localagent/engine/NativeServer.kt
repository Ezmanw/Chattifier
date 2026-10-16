package com.ethan.localagent.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ethan.localagent.data.Attachment
import com.ethan.localagent.data.AttachmentKind
import com.ethan.localagent.util.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * A llama.cpp-family server running as a child process from nativeLibraryDir (the one place
 * Android lets an app execute files), bound to localhost on a free port.
 */
class NativeServer(
    private val context: Context,
    private val executable: String,
    private val args: List<String>,
    private val label: String,
) : AutoCloseable {
    val port: Int = ServerSocket(0).use { it.localPort }
    val baseUrl = "http://127.0.0.1:$port"
    private var process: Process? = null
    private val log = ArrayDeque<String>()

    suspend fun start(http: OkHttpClient, timeoutMs: Long = 180_000) {
        val libDir = context.applicationInfo.nativeLibraryDir
        val argv = listOf("$libDir/$executable") + args + listOf("--host", "127.0.0.1", "--port", port.toString())
        Log.i(TAG, "starting $label: ${argv.joinToString(" ")}")
        val p = ProcessBuilder(argv)
            .redirectErrorStream(true)
            .directory(context.cacheDir)
            .apply {
                // Vendor GPU drivers (OpenCL) live outside our own library folder.
                environment()["LD_LIBRARY_PATH"] = "$libDir:/vendor/lib64:/system/lib64"
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()
        process = p
        Thread {
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { l -> synchronized(log) { log.addLast(l); while (log.size > 80) log.removeFirst() } }
            }
        }.apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            if (!p.isAlive) error("$label stopped: ${tail()}")
            val ok = runCatching {
                http.newCall(Request.Builder().url("$baseUrl/health").build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return
            delay(250)
        }
        close()
        error("$label didn't start in time: ${tail()}")
    }

    fun tail(lines: Int = 6): String = synchronized(log) { log.takeLast(lines).joinToString("\n") }

    val alive: Boolean get() = process?.isAlive == true

    override fun close() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
    }

    companion object { private const val TAG = "NativeServer" }
}

/**
 * Streams OpenAI-style chat completions from a local server, keeping the conversation on our
 * side. Handles text, images (data URLs), audio (input_audio), tool calls, and the Liquid
 * runner's interleaved `audio_chunk` deltas.
 */
class OpenAiSession(
    private val context: Context,
    private val http: OkHttpClient,
    private val server: NativeServer,
    systemPrompt: String,
    history: List<com.ethan.localagent.data.ChatMessage>,
    private val tools: List<ToolSpec>,
    private val temperature: Float,
    private val acceptsImages: Boolean,
    private val acceptsAudio: Boolean,
    /** Extra request fields, e.g. the Liquid runner's reset_context. */
    private val extra: (first: Boolean) -> Map<String, JsonElement> = { emptyMap() },
    /** Sends only new messages each turn (the Liquid server keeps its own context). */
    private val incremental: Boolean = false,
) : Session {
    private val messages = mutableListOf<JsonObject>()
    private var sentCount = 0
    private var call: Call? = null
    private var lastTps: Float? = null

    init {
        if (systemPrompt.isNotBlank()) messages += msg("system", JsonPrimitive(systemPrompt))
        history.filter { it.text.isNotBlank() && !it.error }.forEach {
            messages += msg(if (it.role == com.ethan.localagent.data.Role.USER) "user" else "assistant", JsonPrimitive(it.text))
        }
    }

    override val alive: Boolean get() = server.alive

    private fun msg(role: String, content: JsonElement, extraFields: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
        put("role", role); put("content", content); extraFields.forEach { (k, v) -> put(k, v) }
    }

    override fun send(turn: Turn): Flow<ReplyChunk> = flow {
        when (turn) {
            is Turn.User -> messages += msg("user", userContent(turn))
            is Turn.ToolResults -> turn.results.forEach { (callReq, result) ->
                messages += msg("tool", JsonPrimitive(result), mapOf("tool_call_id" to JsonPrimitive(callReq.id), "name" to JsonPrimitive(callReq.name)))
            }
        }
        val first = sentCount == 0
        val outgoing = if (incremental) messages.drop(sentCount) else messages.toList()
        val body = buildJsonObject {
            put("model", "local")
            put("stream", true)
            put("temperature", temperature)
            put("messages", JsonArray(outgoing))
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { t -> add(buildJsonObject { put("type", "function"); put("function", kotlinx.serialization.json.Json.parseToJsonElement(t.schemaJson)) }) }
            })
            put("timings_per_token", false)
            extra(first).forEach { (k, v) -> put(k, v) }
        }
        sentCount = messages.size
        val req = Request.Builder().url("${server.baseUrl}/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val c = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newCall(req)
        call = c
        var text = ""
        val pending = sortedMapOf<Int, Triple<String, String, StringBuilder>>() // index -> (id, name, args)
        c.execute().use { r ->
            if (!r.isSuccessful) error("Model server error ${r.code}: ${r.body.string().take(300)}")
            val source = r.body.source()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["timings"]?.jsonObject?.get("predicted_per_second")?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.let { lastTps = it }
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject ?: JsonObject(emptyMap())
                val content = delta["content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull.orEmpty()
                val reasoning = delta["reasoning_content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull.orEmpty()
                val audio = (delta["audio_chunk"] as? JsonObject)?.get("data")?.jsonPrimitive?.contentOrNull?.let { decodeFloats(it) }
                (delta["tool_calls"] as? JsonArray)?.forEach { tc ->
                    val o = tc.jsonObject
                    val idx = o["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val fn = o["function"]?.jsonObject
                    val cur = pending.getOrPut(idx) { Triple(o["id"]?.jsonPrimitive?.contentOrNull ?: "call_$idx", fn?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(), StringBuilder()) }
                    val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                    if (!name.isNullOrEmpty() && cur.second.isEmpty()) pending[idx] = Triple(cur.first, name, cur.third)
                    fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { pending[idx]!!.third.append(it) }
                }
                if (content.isNotEmpty() || reasoning.isNotEmpty() || audio != null) {
                    text += content
                    emit(ReplyChunk(text = content, thought = reasoning, audio = audio))
                }
            }
        }
        call = null
        val calls = pending.values.map { (id, name, args) ->
            ToolCallRequest(id, name, runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(args.toString().ifBlank { "{}" }).jsonObject.mapValues { (_, v) -> plain(v) }
            }.getOrDefault(emptyMap()))
        }
        messages += msg("assistant", JsonPrimitive(text), if (calls.isEmpty()) emptyMap() else mapOf("tool_calls" to buildJsonArray {
            calls.forEach { c -> add(buildJsonObject {
                put("id", c.id); put("type", "function")
                put("function", buildJsonObject { put("name", c.name); put("arguments", JsonObject(c.arguments.mapValues { (_, v) -> JsonPrimitive(v?.toString()) }).toString()) })
            }) }
        }))
        if (!incremental) sentCount = messages.size else sentCount = messages.size
        if (calls.isNotEmpty()) emit(ReplyChunk(toolCalls = calls))
    }.flowOn(Dispatchers.IO)

    private suspend fun userContent(turn: Turn.User): JsonElement {
        val parts = mutableListOf<JsonObject>()
        for (a in turn.attachments) parts += mediaParts(a)
        if (turn.text.isNotBlank() || parts.isEmpty()) parts += buildJsonObject { put("type", "text"); put("text", turn.text.ifBlank { " " }) }
        return if (parts.size == 1 && parts[0]["type"]?.jsonPrimitive?.content == "text") parts[0]["text"]!! else JsonArray(parts)
    }

    private suspend fun mediaParts(a: Attachment): List<JsonObject> = when (a.kind) {
        AttachmentKind.IMAGE -> if (acceptsImages) listOf(image(File(a.path))) else emptyList()
        AttachmentKind.AUDIO -> if (acceptsAudio) listOf(audio(File(a.path))) else emptyList()
        AttachmentKind.VIDEO -> buildList {
            if (acceptsImages) Media.videoFrames(context, File(a.path), 6).forEach { add(image(it)) }
            if (acceptsAudio) Media.videoAudioWav(context, File(a.path))?.let { add(audio(it)) }
        }
    }

    private fun image(f: File) = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) })
    }

    private fun audio(f: File) = buildJsonObject {
        put("type", "input_audio")
        put("input_audio", buildJsonObject { put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)); put("format", "wav") })
    }

    private fun decodeFloats(b64: String): FloatArray {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.content.toLongOrNull() ?: e.content.toDoubleOrNull() ?: e.content.toBooleanStrictOrNull() ?: e.content
        else -> e.toString()
    }

    override fun cancel() { call?.cancel() }
    override fun tokensPerSecond(): Float? = lastTps
    override fun close() { cancel() }
}
