package com.ethan.localagent.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ethan.localagent.data.ChatMessage
import com.ethan.localagent.data.PermissionMode
import com.ethan.localagent.data.Prefs
import com.ethan.localagent.data.Role
import com.ethan.localagent.engine.EngineManager
import com.ethan.localagent.engine.Session
import com.ethan.localagent.engine.ToolCallRequest
import com.ethan.localagent.engine.ToolSpec
import com.ethan.localagent.engine.Turn
import com.ethan.localagent.shell.LinuxShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

sealed interface AgentEvent {
    val id: String

    data class Task(override val id: String = uid(), val text: String) : AgentEvent
    data class Reply(override val id: String = uid(), val text: String, val streaming: Boolean = true) : AgentEvent
    data class Tool(
        override val id: String = uid(),
        val name: String,
        val summary: String,
        val status: Status = Status.Running,
        val output: String = "",
        val diff: String? = null,
    ) : AgentEvent {
        enum class Status { Waiting, Running, Done, Failed, Denied }
    }
    data class Note(override val id: String = uid(), val text: String, val error: Boolean = false) : AgentEvent
}

private fun uid() = UUID.randomUUID().toString()

data class Approval(val id: String, val toolName: String, val summary: String, val detail: String, val command: String?)

enum class Decision { ALLOW, DENY, ALWAYS }

data class AgentState(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val events: List<AgentEvent> = emptyList(),
    val approval: Approval? = null,
    val folder: String? = null,
)

/**
 * The coding agent. It loops model → tool calls → results until the model answers without
 * calling a tool. Tools run in this process (files) or the built-in Linux shell (commands);
 * anything that changes the project goes through the permission mode first.
 */
class AgentController(
    private val context: Context,
    private val prefs: Prefs,
    private val engine: EngineManager,
    private val shell: LinuxShell,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()
    private var job: Job? = null
    private var pending: CompletableDeferred<Decision>? = null
    /** Text transcript used to re-seed the model if another screen borrowed the engine. */
    private val transcript = mutableListOf<ChatMessage>()

    fun start(task: String) {
        if (_state.value.running || task.isBlank()) return
        job = scope.launch {
            val settings = prefs.current()
            val folder = settings.codeFolder
            if (folder == null) { note("Choose a project folder first.", error = true); return@launch }
            val root = File(folder)
            if (!root.isDirectory || !root.canRead()) {
                note("Can't open $folder. Give Chattifier “All files access” in Settings, or run background activation.", error = true)
                return@launch
            }
            _state.update { it.copy(running = true, startedAt = System.currentTimeMillis(), folder = folder, events = it.events + AgentEvent.Task(text = task)) }
            if (settings.keepWorking) {
                context.startForegroundService(Intent(context, AgentService::class.java))
            }
            try {
                loop(task, ToolRunner(root, shell), root)
            } catch (e: kotlinx.coroutines.CancellationException) {
                note("Stopped.")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "agent failed", e)
                note(e.message ?: "Something went wrong", error = true)
            } finally {
                pending?.cancel(); pending = null
                _state.update { it.copy(running = false, approval = null) }
                AgentNotifications.clearApproval(context)
            }
        }
    }

    fun stop() {
        engine.cancel()
        job?.cancel()
    }

    fun clear() {
        if (_state.value.running) return
        transcript.clear()
        _state.value = AgentState()
        scope.launch { engine.resetSession(this@AgentController) }
    }

    fun decide(decision: Decision) {
        pending?.complete(decision)
    }

    private suspend fun loop(task: String, tools: ToolRunner, root: File) {
        val system = systemPrompt(root)
        val specs = AGENT_TOOLS.map { ToolSpec(it.name, it.schema) }
        suspend fun session(force: Boolean): Session = engine.sessionFor(this, system, transcript.toList(), specs, forceNew = force)
        var current = session(!engine.owns(this))
        transcript += ChatMessage(role = Role.USER, text = task)
        var next: Turn = Turn.User(task)
        repeat(MAX_STEPS) {
            if (!engine.owns(this)) {
                // Someone else borrowed the model; resume from our transcript as plain text.
                current = session(true)
                if (next is Turn.ToolResults) next = Turn.User(textOf(next as Turn.ToolResults))
            }
            val reply = AgentEvent.Reply(text = "")
            add(reply)
            var text = ""
            val calls = mutableListOf<ToolCallRequest>()
            current.send(next).collect { chunk ->
                if (chunk.text.isNotEmpty()) { text += chunk.text; replace(reply.copy(text = text)) }
                calls += chunk.toolCalls
            }
            // Models without native tool calling can still ask in text: ```tool {"name":…, "arguments":{…}}```
            var textual = false
            if (calls.isEmpty()) parseTextToolCall(text)?.let { calls += it; textual = true; text = text.substringBefore("```tool").trim() }
            if (text.isBlank()) remove(reply.id) else replace(reply.copy(text = text, streaming = false))
            transcript += ChatMessage(role = Role.ASSISTANT, text = text.ifBlank { calls.joinToString { "called ${it.name}" } })
            if (calls.isEmpty()) {
                AgentNotifications.done(context, text.ifBlank { "Finished." })
                return
            }
            val results = calls.map { it to runTool(tools, it.name, it.arguments) }
            val turn = Turn.ToolResults(results)
            transcript += ChatMessage(role = Role.USER, text = textOf(turn).take(4000))
            next = if (textual) Turn.User(textOf(turn)) else turn
        }
        note("Stopped after $MAX_STEPS steps. Send another message to keep going.")
    }

    private fun textOf(t: Turn.ToolResults) = t.results.joinToString("\n\n") { (c, r) -> "Result of ${c.name}:\n$r" }

    private suspend fun runTool(tools: ToolRunner, name: String, args: Map<String, Any?>): String {
        val def = AGENT_TOOLS.firstOrNull { it.name == name }
        val summary = summarize(name, args)
        val event = AgentEvent.Tool(name = name, summary = summary, status = AgentEvent.Tool.Status.Waiting)
        add(event)
        if (def != null && needsApproval(def.kind, args)) {
            val command = args["command"]?.toString()
            val approval = Approval(event.id, name, summary, detail(name, args), command)
            val decision = ask(approval)
            when (decision) {
                Decision.DENY -> {
                    replace(event.copy(status = AgentEvent.Tool.Status.Denied))
                    return "The user denied this. Don't retry the same thing; ask them or try another approach."
                }
                Decision.ALWAYS -> if (command != null) {
                    val word = command.trim().substringBefore(' ')
                    prefs.update { it.copy(allowedCommands = it.allowedCommands + word) }
                } else prefs.update { it.copy(permissionMode = PermissionMode.EDITS) }
                Decision.ALLOW -> {}
            }
        }
        replace(event.copy(status = AgentEvent.Tool.Status.Running))
        val outcome = tools.run(name, args)
        replace(event.copy(status = if (outcome.ok) AgentEvent.Tool.Status.Done else AgentEvent.Tool.Status.Failed, output = outcome.text.take(6000), diff = outcome.diff))
        return outcome.text.take(12_000)
    }

    private suspend fun needsApproval(kind: ToolKind, args: Map<String, Any?>): Boolean {
        val s = prefs.current()
        if (kind == ToolKind.READ) return false
        if (kind == ToolKind.COMMAND) {
            val cmd = args["command"]?.toString().orEmpty().trim()
            val words = cmd.split(Regex("[;&|]+")).map { it.trim().substringBefore(' ') }.filter { it.isNotEmpty() }
            if (words.isNotEmpty() && words.all { it in s.allowedCommands }) return false
        }
        return when (s.permissionMode) {
            PermissionMode.ALWAYS -> false
            PermissionMode.EDITS -> kind == ToolKind.COMMAND
            PermissionMode.ASK -> true
        }
    }

    private suspend fun ask(a: Approval): Decision {
        val d = CompletableDeferred<Decision>()
        pending = d
        _state.update { it.copy(approval = a) }
        if (prefs.current().notifyApprovals) AgentNotifications.approval(context, a)
        return try { d.await() } finally {
            pending = null
            _state.update { it.copy(approval = null) }
            AgentNotifications.clearApproval(context)
        }
    }

    private fun summarize(name: String, a: Map<String, Any?>): String = when (name) {
        "list_files" -> "Listed ${a["path"] ?: "."}"
        "read_file" -> "Read ${a["path"]}"
        "search" -> "Searched for ${a["pattern"]}"
        "write_file" -> "Wrote ${a["path"]}"
        "edit_file" -> "Edited ${a["path"]}"
        "run_command" -> "Ran ${a["command"]}"
        else -> "$name"
    }

    private fun detail(name: String, a: Map<String, Any?>): String = when (name) {
        "run_command" -> a["command"].toString()
        "write_file" -> "${a["path"]}\n\n${a["content"].toString().take(1200)}"
        "edit_file" -> "${a["path"]}\n\n- ${a["old_text"].toString().take(600)}\n+ ${a["new_text"].toString().take(600)}"
        else -> a.toString()
    }

    private fun parseTextToolCall(text: String): ToolCallRequest? {
        val block = Regex("```tool\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return null
        return runCatching {
            val o = agentJson.parseToJsonElement(block).jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val args = (o["arguments"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() } ?: emptyMap()
            ToolCallRequest(UUID.randomUUID().toString(), name, args)
        }.getOrNull()
    }

    private fun systemPrompt(root: File): String = """
        You are Chattifier's coding agent, running entirely on the user's Android phone.
        You work inside the project folder ${root.path}. All tool paths are relative to it.
        ${if (shell.ready) "A real Alpine Linux shell is available through run_command (bash, git, python3, node, npm, ripgrep)." else "The Linux shell isn't installed, so run_command won't work; use the file tools."}
        How to work:
        - Look before you change anything: list_files, search and read_file first.
        - Make small, correct edits with edit_file; use write_file for new files.
        - Run the project's own checks (tests, linters, builds) with run_command when they exist.
        - If a tool fails, read the error and adjust. Don't repeat the same failing call.
        - When the task is done, reply with a short summary of what you changed. No tool call in that reply.
        If your runtime can't call tools natively, call one by replying with exactly:
        ```tool
        {"name": "read_file", "arguments": {"path": "README.md"}}
        ```
    """.trimIndent()

    private fun add(e: AgentEvent) = _state.update { it.copy(events = it.events + e) }
    private fun replace(e: AgentEvent) = _state.update { s -> s.copy(events = s.events.map { if (it.id == e.id) e else it }) }
    private fun remove(id: String) = _state.update { s -> s.copy(events = s.events.filterNot { it.id == id }) }
    private fun note(text: String, error: Boolean = false) = add(AgentEvent.Note(text = text, error = error))

    companion object {
        private const val TAG = "Agent"
        private const val MAX_STEPS = 40
    }
}
