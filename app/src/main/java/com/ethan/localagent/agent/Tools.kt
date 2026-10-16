package com.ethan.localagent.agent

import com.ethan.localagent.shell.LinuxShell
import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

enum class ToolKind { READ, EDIT, COMMAND, PHONE }

/** A tool the agent can call. Schemas follow the OpenAPI function format LiteRT-LM expects. */
data class AgentTool(
    val name: String,
    val description: String,
    val kind: ToolKind,
    val params: List<Param>,
) {
    data class Param(val name: String, val type: String, val description: String, val required: Boolean = true)

    val schema: String by lazy {
        buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    params.forEach { p -> putJsonObject(p.name) { put("type", p.type); put("description", p.description) } }
                }
                putJsonArray("required") { params.filter { it.required }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } }
            }
        }.toString()
    }

    /** LiteRT only needs the description; we run tools ourselves so we can ask for approval first. */
    fun asLiteRt(): OpenApiTool = object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = schema
        override fun execute(paramsJsonString: String): String = "{}"
    }
}

val AGENT_TOOLS = listOf(
    AgentTool(
        "list_files", "List files and folders inside the project. Use '.' for the project root.", ToolKind.READ,
        listOf(AgentTool.Param("path", "string", "Folder relative to the project root"), AgentTool.Param("depth", "integer", "How deep to go, 1-4", required = false)),
    ),
    AgentTool(
        "read_file", "Read a text file from the project, optionally a line range.", ToolKind.READ,
        listOf(
            AgentTool.Param("path", "string", "File relative to the project root"),
            AgentTool.Param("start_line", "integer", "First line, 1-based", required = false),
            AgentTool.Param("end_line", "integer", "Last line, inclusive", required = false),
        ),
    ),
    AgentTool(
        "search", "Search the project for a regular expression using ripgrep. Returns matching lines with paths and line numbers.", ToolKind.READ,
        listOf(AgentTool.Param("pattern", "string", "Regular expression"), AgentTool.Param("path", "string", "Folder to search, default '.'", required = false)),
    ),
    AgentTool(
        "write_file", "Create or overwrite a file with the given content.", ToolKind.EDIT,
        listOf(AgentTool.Param("path", "string", "File relative to the project root"), AgentTool.Param("content", "string", "The whole new file content")),
    ),
    AgentTool(
        "edit_file", "Replace one exact occurrence of old_text with new_text in a file. old_text must match exactly once.", ToolKind.EDIT,
        listOf(
            AgentTool.Param("path", "string", "File relative to the project root"),
            AgentTool.Param("old_text", "string", "Exact text to replace"),
            AgentTool.Param("new_text", "string", "Replacement text"),
        ),
    ),
    AgentTool(
        "run_command", "Run a bash command in the project folder inside the built-in Alpine Linux shell (git, python3, node, npm, ripgrep available). Returns combined output and exit code.", ToolKind.COMMAND,
        listOf(AgentTool.Param("command", "string", "The bash command"), AgentTool.Param("timeout_seconds", "integer", "Default 120, max 1800", required = false)),
    ),
)

data class ToolOutcome(val text: String, val ok: Boolean, val diff: String? = null)

/** Executes tool calls against one project folder. Nothing may escape [root]. */
class ToolRunner(private val root: File, private val shell: LinuxShell) {
    private val canonicalRoot = root.canonicalFile

    private fun resolve(path: String?): File {
        val p = (path ?: ".").trim().removePrefix(canonicalRoot.path).trimStart('/').ifEmpty { "." }
        val f = File(canonicalRoot, p).canonicalFile
        require(f.path == canonicalRoot.path || f.path.startsWith(canonicalRoot.path + "/")) { "That path is outside the project folder" }
        return f
    }

    fun rel(f: File): String = f.canonicalFile.relativeToOrNull(canonicalRoot)?.path?.ifEmpty { "." } ?: f.path

    suspend fun run(name: String, args: Map<String, Any?>): ToolOutcome = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "list_files" -> listFiles(resolve(args.str("path")), (args.int("depth") ?: 2).coerceIn(1, 4))
                "read_file" -> readFile(resolve(args.str("path")), args.int("start_line"), args.int("end_line"))
                "search" -> search(args.str("pattern") ?: error("pattern is required"), resolve(args.str("path")))
                "write_file" -> writeFile(resolve(args.str("path")), args.str("content") ?: "")
                "edit_file" -> editFile(resolve(args.str("path")), args.str("old_text") ?: error("old_text is required"), args.str("new_text") ?: "")
                "run_command" -> runCommand(args.str("command") ?: error("command is required"), (args.int("timeout_seconds") ?: 120).coerceIn(5, 1800))
                else -> ToolOutcome("Unknown tool '$name'. Available: ${AGENT_TOOLS.joinToString { it.name }}", ok = false)
            }
        } catch (e: Exception) {
            ToolOutcome("Error: ${e.message}", ok = false)
        }
    }

    private fun listFiles(dir: File, depth: Int): ToolOutcome {
        require(dir.isDirectory) { "${rel(dir)} is not a folder" }
        val out = StringBuilder()
        var count = 0
        fun walk(d: File, level: Int) {
            val kids = d.listFiles().orEmpty().filterNot { it.name in IGNORED }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            for (k in kids) {
                if (count++ > 400) return
                out.append("  ".repeat(level)).append(k.name).append(if (k.isDirectory) "/" else "  (${k.length()} B)").append('\n')
                if (k.isDirectory && level + 1 < depth) walk(k, level + 1)
            }
        }
        walk(dir, 0)
        if (count > 400) out.append("… more files not shown\n")
        return ToolOutcome(out.toString().ifEmpty { "(empty folder)" }, ok = true)
    }

    private fun readFile(f: File, start: Int?, end: Int?): ToolOutcome {
        require(f.isFile) { "${rel(f)} doesn't exist" }
        require(f.length() < 2_000_000) { "${rel(f)} is too big to read whole; use a line range or search" }
        val lines = f.readLines()
        val s = (start ?: 1).coerceAtLeast(1)
        val e = (end ?: minOf(lines.size, s + 399)).coerceAtMost(lines.size)
        val body = (s..e).joinToString("\n") { "%5d  %s".format(it, lines[it - 1]) }
        val more = if (e < lines.size) "\n… ${lines.size - e} more lines (use start_line/end_line)" else ""
        return ToolOutcome(body + more, ok = true)
    }

    private suspend fun search(pattern: String, dir: File): ToolOutcome {
        if (shell.ready) {
            val r = shell.run("rg -n --no-heading --max-count 20 --max-columns 300 -e ${sh(pattern)} ${sh(dir.path)} | head -200", dir.path)
            val text = r.output.replace(canonicalRoot.path + "/", "")
            return ToolOutcome(text.ifBlank { "No matches" }, ok = true)
        }
        val re = Regex(pattern)
        val hits = mutableListOf<String>()
        dir.walkTopDown().onEnter { it.name !in IGNORED }.filter { it.isFile && it.length() < 1_000_000 }.forEach { f ->
            if (hits.size >= 200) return@forEach
            runCatching { f.readLines() }.getOrNull()?.forEachIndexed { i, l -> if (hits.size < 200 && re.containsMatchIn(l)) hits += "${rel(f)}:${i + 1}:${l.take(300)}" }
        }
        return ToolOutcome(hits.joinToString("\n").ifEmpty { "No matches" }, ok = true)
    }

    private fun writeFile(f: File, content: String): ToolOutcome {
        val before = if (f.exists()) f.readText() else null
        f.parentFile?.mkdirs()
        f.writeText(content)
        val added = content.lines().size
        val removed = before?.lines()?.size ?: 0
        return ToolOutcome("Wrote ${rel(f)} (${content.length} chars)", ok = true, diff = "+$added −$removed")
    }

    private fun editFile(f: File, old: String, new: String): ToolOutcome {
        require(f.isFile) { "${rel(f)} doesn't exist" }
        val text = f.readText()
        var count = 0
        var at = if (old.isEmpty()) -1 else text.indexOf(old)
        while (at >= 0) { count++; at = text.indexOf(old, at + 1) }
        require(count == 1) { if (count == 0) "old_text wasn't found in ${rel(f)}. Read the file again and copy it exactly." else "old_text matches $count places; include more surrounding lines" }
        f.writeText(text.replaceFirst(old, new))
        return ToolOutcome("Edited ${rel(f)}", ok = true, diff = "+${new.lines().size} −${old.lines().size}")
    }

    private suspend fun runCommand(command: String, timeoutS: Int): ToolOutcome {
        require(shell.ready) { "The Linux shell isn't installed yet. Ask the user to set it up from the Code tab." }
        val r = shell.run(command, canonicalRoot.path, timeoutS * 1000L)
        val status = if (r.timedOut) "timed out after ${timeoutS}s" else "exit ${r.exitCode}"
        return ToolOutcome("$ $command\n${r.output.trimEnd()}\n[$status]", ok = r.exitCode == 0)
    }

    companion object {
        val IGNORED = setOf(".git", "node_modules", "build", ".gradle", ".dart_tool", ".idea", "__pycache__", ".venv", "target")
        fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}

private fun Map<String, Any?>.str(k: String): String? = this[k]?.toString()
private fun Map<String, Any?>.int(k: String): Int? = when (val v = this[k]) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}

internal val agentJson = Json { ignoreUnknownKeys = true; isLenient = true }
