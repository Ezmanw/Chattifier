package com.ethan.localagent.engine

import android.content.Context
import com.ethan.localagent.data.ChatMessage
import com.ethan.localagent.data.ComputeBackend
import com.ethan.localagent.data.ModelEntry
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/** GGUF models through our own llama.cpp build (llama-server), CPU or Adreno GPU via OpenCL. */
class GgufModel private constructor(
    private val context: Context,
    private val http: OkHttpClient,
    private val model: ModelEntry,
    private val server: NativeServer,
) : LoadedModel {

    override fun open(systemPrompt: String, history: List<ChatMessage>, tools: List<ToolSpec>, temperature: Float): Session =
        OpenAiSession(context, http, server, systemPrompt, history, tools, temperature, acceptsImages = model.vision, acceptsAudio = model.audio)

    override fun close() = server.close()

    companion object {
        suspend fun load(context: Context, http: OkHttpClient, model: ModelEntry, backend: ComputeBackend, contextTokens: Int): GgufModel {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { (it * 3 / 4).coerceAtLeast(2) }
            val args = buildList {
                add("-m"); add(model.path)
                model.projectorPath?.let { add("--mmproj"); add(it) }
                add("-c"); add(contextTokens.toString())
                add("-t"); add(threads.toString())
                add("-ngl"); add(if (backend == ComputeBackend.GPU) "99" else "0")
                if (backend == ComputeBackend.CPU) { add("--device"); add("none") }
                add("--jinja")
                add("--no-webui")
                add("-np"); add("1")
                add("--cache-reuse"); add("256")
            }
            val server = NativeServer(context, "libllama_server.so", args, model.name)
            try { server.start(http) } catch (e: Exception) { server.close(); throw e }
            return GgufModel(context, http, model, server)
        }
    }
}

/**
 * LFM2.5-Audio: true speech-to-speech. Hears audio, answers with text and its own synthesized
 * voice (24 kHz), streamed as it's generated. Runs Liquid AI's runner on the CPU.
 */
class SpeechModel private constructor(
    private val context: Context,
    private val http: OkHttpClient,
    private val server: NativeServer,
) : LoadedModel {

    override fun open(systemPrompt: String, history: List<ChatMessage>, tools: List<ToolSpec>, temperature: Float): Session =
        OpenAiSession(
            context, http, server,
            // The runner switches modes on its system prompt; interleaved means "answer with text + speech".
            systemPrompt = "Respond with interleaved text and audio. $systemPrompt".trim(),
            history = emptyList(), tools = emptyList(), temperature = temperature,
            acceptsImages = false, acceptsAudio = true,
            extra = { first -> mapOf("reset_context" to JsonPrimitive(first), "max_tokens" to JsonPrimitive(512)) },
            incremental = true,
        )

    override fun close() = server.close()

    companion object {
        suspend fun load(context: Context, http: OkHttpClient, model: ModelEntry): SpeechModel {
            val args = listOf(
                "-m", model.path,
                "-mm", model.projectorPath ?: error("Missing the audio encoder file"),
                "-mv", model.vocoderPath ?: error("Missing the vocoder file"),
                "--tts-speaker-file", model.speakerPath ?: error("Missing the speaker tokenizer file"),
            )
            val server = NativeServer(context, "libliquid_audio_server.so", args, model.name)
            try { server.start(http) } catch (e: Exception) { server.close(); throw e }
            return SpeechModel(context, http, server)
        }
    }
}
