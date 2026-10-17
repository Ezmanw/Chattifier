package com.ethan.localagent.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ethan.localagent.data.Attachment
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

@Serializable
sealed interface AgentEvent {
    val id: String

    @Serializable data class Task(override val id: String = uid(), val text: String) : AgentEvent
    @Serializable data class Reply(override val id: String = uid(), val text: String, val streaming: Boolean = true) : AgentEvent
    @Serializable data class Tool(
        override val id: String = uid(),
        val name: String,
        val summary: String,
        val status: Status = Status.Running,
        val output: String = "",
        val diff: String? = null,
        val attachments: List<Attachment> = emptyList(),
    ) : AgentEvent {
        @Serializable enum class Status { Waiting, Running, Done, Failed, Denied }
    }
    @Serializable data class Note(override val id: String = uid(), val text: String, val error: Boolean = false) : AgentEvent
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
    val sessionId: String? = null,
)

/** A tool result, possibly carrying an image (a screenshot) the model should see. */
private data class ToolRun(val text: String, val attachments: List<Attachment> = emptyList())

/**
 * The coding agent. It loops model → tool calls → results until the model answers without
 * calling a tool. Tools run in this process (files), the built-in Linux shell (commands), or
 * through the wireless-debugging link (phone control). Anything that changes the project goes
 * through the permission mode first; work is grouped into persistent, switchable sessions.
 */
class AgentController(
    private val context: Context,
    private val prefs: Prefs,
    private val engine: EngineManager,
    private val shell: LinuxShell,
    private val phone: PhoneController,
    private val sessions: CodeSessionRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()
    private var job: Job? = null
    private var pending: CompletableDeferred<Decision>? = null
    /** Text transcript used to re-seed the model if another screen borrowed the engine. */
    private val transcript = mutableListOf<ChatMessage>()
    /** Notes the user adds while the agent is running; picked up at the next step boundary. */
    private val noteQueue = Channel<String>(Channel.UNLIMITED)
    private var sessionId: String? = null

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
            val session = currentSession(folder)
            _state.update {
                it.copy(running = true, startedAt = System.currentTimeMillis(), folder = folder, sessionId = session.id, events = it.events + AgentEvent.Task(text = task))
            }
            if (settings.keepWorking) {
                context.startForegroundService(Intent(context, AgentService::class.java))
            }
            try {
                loop(task, ToolRunner(root, shell), root)
                persist()
            } catch (e: kotlinx.coroutines.CancellationException) {
                note("Stopped.")
                persist()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "agent failed", e)
                note(e.message ?: "Something went wrong", error = true)
                persist()
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
        _state.update { it.copy(events = emptyList()) }
        scope.launch { engine.resetSession(this@AgentController); persist() }
    }

    /** Adds a note to the running agent. It appears right away and is acted on at the next step. */
    fun sendNote(text: String) {
        if (!_state.value.running || text.isBlank()) return
        add(AgentEvent.Task(text = text))
        noteQueue.trySend(text)
        persist()
    }

    fun openSession(id: String) {
        if (_state.value.running) return
        scope.launch {
            val s = sessions.load(id) ?: return@launch
            sessionId = s.id
            transcript.clear()
            transcript += s.messages
            _state.value = AgentState(
                events = s.events.map { e -> if (e is AgentEvent.Reply) e.copy(streaming = false) else e },
                folder = s.folder,
                sessionId = s.id,
            )
            engine.resetSession(this@AgentController)
        }
    }

    fun newSession(folder: String?) {
        if (_state.value.running) return
        scope.launch {
            val s = sessions.create(folder)
            sessionId = s.id
            transcript.clear()
            _state.value = AgentState(folder = folder, sessionId = s.id)
            engine.resetSession(this@AgentController)
        }
    }

    fun deleteSession(id: String) {
        if (_state.value.running && sessionId == id) return
        scope.launch {
            sessions.delete(id)
            if (sessionId == id) {
                val next = sessions.summaries.value.firstOrNull()?.id
                if (next != null) openSession(next)
                else {
                    sessionId = null
                    transcript.clear()
                    _state.value = AgentState()
                }
            }
        }
    }

    fun renameSession(id: String, title: String) {
        scope.launch {
            sessions.load(id)?.let { sessions.save(it.copy(title = title.trim().ifBlank { "New task" })) }
        }
    }

    fun decide(decision: Decision) {
        pending?.complete(decision)
    }

    private suspend fun currentSession(folder: String?): CodeSession = withContext(Dispatchers.IO) {
        sessions.refresh()
        val id = sessionId ?: sessions.summaries.value.firstOrNull()?.id
        val existing = id?.let { sessions.load(it) }
        existing ?: sessions.create(folder).also {
            sessionId = it.id
            _state.update { s -> s.copy(sessionId = it.id) }
        }
    }

    private suspend fun loop(task: String, tools: ToolRunner, root: File) {
        val phoneOn = prefs.current().phoneControl
        val system = systemPrompt(root, phoneOn)
        val specs = (AGENT_TOOLS + if (phoneOn) PHONE_TOOLS else emptyList()).map { ToolSpec(it.name, it.schema) }
        // Code mode can run a different model than chat (e.g. a strong tool-calling GGUF).
        val codeModel = prefs.current().codeModelId
        suspend fun session(force: Boolean): Session = engine.sessionFor(this, system, transcript.toList(), specs, forceNew = force, modelId = codeModel)
        var current = session(!engine.owns(this))
        transcript += ChatMessage(role = Role.USER, text = task)
        var next: Turn = Turn.User(task)
        repeat(MAX_STEPS) {
            if (!engine.owns(this)) {
                current = session(true)
                if (next is Turn.ToolResults) next = Turn.User(textOf(next as Turn.ToolResults))
            }
            noteQueue.tryReceive().getOrNull()?.let { noteText ->
                // Re-seed from the full transcript (which already holds any tool results we're
                // pre-empting), then send just the note as the new user turn.
                current = session(true)
                transcript += ChatMessage(role = Role.USER, text = noteText)
                next = Turn.User(noteText)
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
            val toolTurn = Turn.ToolResults(results.map { (c, r) -> c to r.text })
            val shots = results.flatMap { (_, r) -> r.attachments }
            transcript += ChatMessage(role = Role.USER, text = textOf(toolTurn).take(4000))
            next = if (textual || shots.isNotEmpty()) Turn.User(textOf(toolTurn), shots) else toolTurn
            persist()
        }
        note("Stopped after $MAX_STEPS steps. Send another message to keep going.")
    }

    private fun textOf(t: Turn.ToolResults) = t.results.joinToString("\n\n") { (c, r) -> "Result of ${c.name}:\n$r" }

    private suspend fun runTool(tools: ToolRunner, name: String, args: Map<String, Any?>): ToolRun {
        val def = (AGENT_TOOLS + PHONE_TOOLS).firstOrNull { it.name == name }
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
                    return ToolRun("The user denied this. Don't retry the same thing; ask them or try another approach.")
                }
                Decision.ALWAYS -> if (command != null) {
                    val word = command.trim().substringBefore(' ')
                    prefs.update { it.copy(allowedCommands = it.allowedCommands + word) }
                } else prefs.update { it.copy(permissionMode = PermissionMode.EDITS) }
                Decision.ALLOW -> {}
            }
        }
        replace(event.copy(status = AgentEvent.Tool.Status.Running))

        // A screenshot returns an image the vision model must actually see.
        if (name == "take_screenshot") {
            return try {
                val shot = phone.screenshot()
                replace(event.copy(status = AgentEvent.Tool.Status.Done, summary = "Screen ${shot.width}×${shot.height}", attachments = listOf(shot.attachment)))
                ToolRun("Screenshot taken (${shot.width}×${shot.height} px). The current screen is attached as an image. Use these pixel coordinates for screen_tap and screen_swipe.", listOf(shot.attachment))
            } catch (e: Exception) {
                replace(event.copy(status = AgentEvent.Tool.Status.Failed, output = e.message ?: "Screenshot failed"))
                ToolRun("Screenshot failed: ${e.message}. Make sure wireless-debugging is connected (Code settings › Background access).")
            }
        }

        val outcome = if (PHONE_TOOLS.any { it.name == name }) phone.run(name, args) else tools.run(name, args)
        replace(event.copy(status = if (outcome.ok) AgentEvent.Tool.Status.Done else AgentEvent.Tool.Status.Failed, output = outcome.text.take(6000), diff = outcome.diff))
        return ToolRun(outcome.text.take(12_000))
    }

    private suspend fun needsApproval(kind: ToolKind, args: Map<String, Any?>): Boolean {
        val s = prefs.current()
        if (kind == ToolKind.READ || kind == ToolKind.PHONE) return false
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
        "take_screenshot" -> "Took a screenshot"
        "screen_tap" -> "Tapped ${a["x"]}, ${a["y"]}"
        "screen_swipe" -> "Swiped from ${a["x1"]},${a["y1"]}"
        "screen_type" -> "Typed “${a["text"]?.toString()?.take(40) ?: ""}”"
        "screen_key" -> "Pressed ${a["key"]}"
        "open_app" -> "Opened ${a["package"]}"
        "adb_shell" -> "Ran ${a["command"]}"
        else -> "$name"
    }

    private fun detail(name: String, a: Map<String, Any?>): String = when (name) {
        "run_command" -> a["command"].toString()
        "adb_shell" -> a["command"].toString()
        "write_file" -> "${a["path"]}\n\n${a["content"].toString().take(1200)}"
        "edit_file" -> "${a["path"]}\n\n- ${a["old_text"].toString().take(600)}\n+ ${a["new_text"].toString().take(600)}"
        else -> a.toString()
    }

    private fun parseTextToolCall(text: String): ToolCallRequest? {
        // Note: the closing brace must be escaped — Android's regex engine rejects a bare '}'.
        val block = Regex("```tool\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return null
        return runCatching {
            val o = agentJson.parseToJsonElement(block).jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val args = (o["arguments"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() } ?: emptyMap()
            ToolCallRequest(UUID.randomUUID().toString(), name, args)
        }.getOrNull()
    }

    private fun systemPrompt(root: File, phoneOn: Boolean): String = """
        You are Chattifier's coding agent, running entirely on the user's Android phone.
        You work inside the project folder ${root.path}. All tool paths are relative to it.
        ${if (shell.ready) "A real Alpine Linux shell is available through run_command (bash, git, python3, node, npm, ripgrep)." else "The Linux shell isn't installed, so run_command won't work; use the file tools."}
        ${if (phoneOn) "You also control the phone over its wireless-debugging link. Call take_screenshot first to see the screen, then screen_tap / screen_swipe / screen_type / screen_key / open_app, and take_screenshot again after each action to check the result. Screenshot coordinates are full-resolution pixels; use the numbers from the latest screenshot for taps." else ""}
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

    private fun persist() {
        val id = sessionId ?: return
        val events = _state.value.events
        val messages = transcript.toList()
        val title = events.filterIsInstance<AgentEvent.Task>().firstOrNull()?.text?.trim()?.take(60) ?: "New task"
        scope.launch(Dispatchers.IO) {
            sessions.load(id)?.let { s ->
                sessions.save(s.copy(messages = messages, events = events, title = title, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private fun add(e: AgentEvent) = _state.update { it.copy(events = it.events + e) }
    private fun replace(e: AgentEvent) = _state.update { s -> s.copy(events = s.events.map { if (it.id == e.id) e else it }) }
    private fun remove(id: String) = _state.update { s -> s.copy(events = s.events.filterNot { it.id == id }) }
    private fun note(text: String, error: Boolean = false) = add(AgentEvent.Note(text = text, error = error))

    companion object {
        private const val TAG = "Agent"
        private const val MAX_STEPS = 40
    }
}
