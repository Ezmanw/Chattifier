package com.ethan.localagent.engine

import android.content.Context
import com.ethan.localagent.data.AttachmentKind
import com.ethan.localagent.data.ChatMessage
import com.ethan.localagent.data.ComputeBackend
import com.ethan.localagent.data.ModelEntry
import com.ethan.localagent.data.Role
import com.ethan.localagent.util.Media
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.UUID

/** Google's LiteRT-LM runtime: .litertlm files on CPU, Adreno GPU or Hexagon NPU. */
class LiteRtModel private constructor(private val context: Context, private val model: ModelEntry, private val engine: Engine) : LoadedModel {

    override fun open(systemPrompt: String, history: List<ChatMessage>, tools: List<ToolSpec>, temperature: Float): Session {
        val initial = history.filter { it.text.isNotBlank() && !it.error }.map {
            if (it.role == Role.USER) Message.user(it.text) else Message.model(it.text)
        }
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = initial,
            tools = tools.map { spec ->
                tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString() = spec.schemaJson
                    override fun execute(paramsJsonString: String) = "{}"
                })
            },
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature.toDouble(), seed = 0),
            automaticToolCalling = false,
        )
        return LiteRtSession(engine.createConversation(config))
    }

    private inner class LiteRtSession(private val conv: Conversation) : Session {
        override val alive: Boolean get() = conv.isAlive

        override fun send(turn: Turn): Flow<ReplyChunk> = flow {
            val message = when (turn) {
                is Turn.User -> userMessage(turn)
                is Turn.ToolResults -> Message.tool(Contents.of(turn.results.map { (call, result) -> Content.ToolResponse(call.name, result) }))
            }
            conv.sendMessageAsync(message).collect { m ->
                emit(
                    ReplyChunk(
                        text = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text },
                        thought = m.channels["thought"].orEmpty(),
                        toolCalls = m.toolCalls.map { ToolCallRequest(UUID.randomUUID().toString(), it.name, it.arguments) },
                    ),
                )
            }
        }.flowOn(Dispatchers.IO)

        private suspend fun userMessage(turn: Turn.User): Message {
            val parts = mutableListOf<Content>()
            for (a in turn.attachments) when (a.kind) {
                AttachmentKind.IMAGE -> if (model.vision) parts += Content.ImageFile(a.path)
                AttachmentKind.AUDIO -> if (model.audio) parts += Content.AudioFile(a.path)
                AttachmentKind.VIDEO -> {
                    if (model.vision) Media.videoFrames(context, File(a.path), MAX_IMAGES).forEach { parts += Content.ImageFile(it.absolutePath) }
                    if (model.audio) Media.videoAudioWav(context, File(a.path))?.let { parts += Content.AudioFile(it.absolutePath) }
                }
            }
            if (turn.text.isNotBlank()) parts += Content.Text(turn.text)
            if (parts.isEmpty()) parts += Content.Text(" ")
            return Message.user(Contents.of(parts))
        }

        override fun cancel() { runCatching { conv.cancelProcess() } }

        override fun tokensPerSecond(): Float? =
            runCatching { conv.getBenchmarkInfo().lastDecodeTokensPerSecond.toFloat() }.getOrNull()?.takeIf { it > 0f }

        override fun close() { runCatching { conv.close() } }
    }

    override fun close() { runCatching { engine.close() } }

    companion object {
        const val MAX_IMAGES = 8

        fun load(context: Context, model: ModelEntry, backend: ComputeBackend, contextTokens: Int): LiteRtModel {
            val main: Backend = when (backend) {
                ComputeBackend.CPU -> Backend.CPU()
                ComputeBackend.GPU -> Backend.GPU()
                ComputeBackend.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
            }
            val vision: Backend? = if (model.vision) (if (backend == ComputeBackend.CPU) Backend.CPU() else Backend.GPU()) else null
            // Audio encoders are only shipped for CPU in current LiteRT-LM builds.
            val audio: Backend? = if (model.audio) Backend.CPU() else null
            val cache = File(context.cacheDir, "litert").apply { mkdirs() }
            val config = EngineConfig(model.path, main, vision, audio, contextTokens, if (model.vision) MAX_IMAGES else null, cache.absolutePath)
            val engine = Engine(config)
            engine.initialize()
            return LiteRtModel(context, model, engine)
        }
    }
}
