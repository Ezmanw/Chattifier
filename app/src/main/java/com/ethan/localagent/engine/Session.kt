package com.ethan.localagent.engine

import com.ethan.localagent.data.Attachment
import kotlinx.coroutines.flow.Flow

/** A tool call requested by the model, independent of which runtime produced it. */
data class ToolCallRequest(val id: String, val name: String, val arguments: Map<String, Any?>)

/** What we send the model next. */
sealed interface Turn {
    data class User(val text: String, val attachments: List<Attachment> = emptyList()) : Turn
    data class ToolResults(val results: List<Pair<ToolCallRequest, String>>) : Turn
}

/** One streamed piece of a reply: a text delta, reasoning, tool calls, or synthesized audio. */
data class ReplyChunk(
    val text: String = "",
    val thought: String = "",
    val toolCalls: List<ToolCallRequest> = emptyList(),
    /** Mono float PCM at [audioSampleRate], only from speech-to-speech models. */
    val audio: FloatArray? = null,
    val audioSampleRate: Int = 24_000,
)

/** A tool description in the OpenAI/OpenAPI function format both runtimes understand. */
data class ToolSpec(val name: String, val schemaJson: String)

/** A live conversation with the loaded model. */
interface Session : AutoCloseable {
    val alive: Boolean
    fun send(turn: Turn): Flow<ReplyChunk>
    fun cancel()
    /** Decode speed of the last reply, if the runtime reports it. */
    fun tokensPerSecond(): Float? = null
}

/** A loaded model that can open sessions. */
interface LoadedModel : AutoCloseable {
    fun open(systemPrompt: String, history: List<com.ethan.localagent.data.ChatMessage>, tools: List<ToolSpec>, temperature: Float): Session
}
