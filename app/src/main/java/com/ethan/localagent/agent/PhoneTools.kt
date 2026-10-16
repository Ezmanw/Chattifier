package com.ethan.localagent.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.ethan.localagent.adb.AdbActivator
import com.ethan.localagent.data.Attachment
import com.ethan.localagent.data.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Lets the agent see and drive the phone through the wireless-debugging link that onboarding
 * already paired. The model gets a fresh screenshot after every action so it can decide the next
 * tap, which is what makes "do my homework in this app" possible without a PC.
 */
class PhoneController(private val adb: AdbActivator, private val captureDir: File) {

    data class Shot(val attachment: Attachment, val width: Int, val height: Int)

    suspend fun connected(): Boolean = withContext(Dispatchers.IO) { runCatching { adb.ensureConnected() }.getOrDefault(false) }

    /** Screenshot saved as an app-private JPEG the model can be shown, downscaled for the vision encoder. */
    suspend fun screenshot(): Shot = withContext(Dispatchers.IO) {
        val png = adb.screenshot()
        val full = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("Couldn't read the screenshot")
        val scale = (1280f / maxOf(full.width, full.height)).coerceAtMost(1f)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true) else full
        val file = File(captureDir, "screen_${UUID.randomUUID().toString().take(8)}.jpg").apply { parentFile?.mkdirs() }
        ByteArrayOutputStream().use { bos -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos); file.writeBytes(bos.toByteArray()) }
        val shot = Shot(Attachment(AttachmentKind.IMAGE, file.absolutePath, "screen"), full.width, full.height)
        if (scaled !== full) scaled.recycle()
        full.recycle()
        shot
    }

    suspend fun run(name: String, args: Map<String, Any?>): ToolOutcome = withContext(Dispatchers.IO) {
        if (!connected()) return@withContext ToolOutcome("Not connected to wireless debugging. Ask the user to run background activation in Code settings.", ok = false)
        try {
            when (name) {
                "screen_tap" -> { input("tap ${args.int("x")} ${args.int("y")}"); settle(); ToolOutcome("Tapped ${args.int("x")}, ${args.int("y")}", ok = true) }
                "screen_swipe" -> { input("swipe ${args.int("x1")} ${args.int("y1")} ${args.int("x2")} ${args.int("y2")} ${args.int("ms") ?: 300}"); settle(); ToolOutcome("Swiped", ok = true) }
                "screen_type" -> { val t = args.str("text").orEmpty(); input("text ${shellArg(t.replace(" ", "%s"))}"); settle(); ToolOutcome("Typed “${t.take(60)}”", ok = true) }
                "screen_key" -> { input("keyevent ${keycode(args.str("key").orEmpty())}"); settle(); ToolOutcome("Pressed ${args.str("key")}", ok = true) }
                "open_app" -> openApp(args.str("package").orEmpty())
                "adb_shell" -> {
                    val (ok, out) = adb.runShell(args.str("command").orEmpty())
                    ToolOutcome("$ ${args.str("command")}\n${out.trim()}", ok = ok)
                }
                else -> ToolOutcome("Unknown phone tool '$name'", ok = false)
            }
        } catch (e: Exception) {
            ToolOutcome("Error: ${e.message}", ok = false)
        }
    }

    private fun input(cmd: String) = adb.runShell("input $cmd")

    private fun openApp(pkg: String): ToolOutcome {
        if (pkg.isBlank()) return ToolOutcome("Give the app's package name, e.g. com.google.android.apps.docs", ok = false)
        val (ok, out) = adb.runShell("monkey -p ${shellArg(pkg)} -c android.intent.category.LAUNCHER 1")
        return ToolOutcome(if (ok) "Opened $pkg" else "Couldn't open $pkg: $out", ok = ok)
    }

    private suspend fun settle() = delay(600)

    private fun keycode(key: String): String = when (key.lowercase()) {
        "back" -> "4"; "home" -> "3"; "enter" -> "66"; "tab" -> "61"; "delete", "backspace" -> "67"
        "recents", "overview" -> "187"; "up" -> "19"; "down" -> "20"; "left" -> "21"; "right" -> "22"
        "space" -> "62"; "search" -> "84"; else -> key
    }

    private fun shellArg(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun Map<String, Any?>.int(k: String): Int? = when (val v = this[k]) {
        is Number -> v.toInt(); is String -> v.toIntOrNull(); else -> null
    }
    private fun Map<String, Any?>.str(k: String): String? = this[k]?.toString()
}

/** Tool specs offered only when the user turns on phone control. */
val PHONE_TOOLS = listOf(
    AgentTool("take_screenshot", "Take a screenshot of the phone so you can see what's on screen. Do this before tapping, and after an action to check the result. The screenshot's pixel size is given so your tap coordinates match.", ToolKind.READ, emptyList()),
    AgentTool("screen_tap", "Tap the screen at pixel x, y (full-resolution coordinates from the latest screenshot).", ToolKind.PHONE, listOf(AgentTool.Param("x", "integer", "x pixels"), AgentTool.Param("y", "integer", "y pixels"))),
    AgentTool("screen_swipe", "Swipe/scroll from x1,y1 to x2,y2 over ms milliseconds.", ToolKind.PHONE, listOf(AgentTool.Param("x1", "integer", ""), AgentTool.Param("y1", "integer", ""), AgentTool.Param("x2", "integer", ""), AgentTool.Param("y2", "integer", ""), AgentTool.Param("ms", "integer", "duration", required = false))),
    AgentTool("screen_type", "Type text into the focused field. Tap the field first.", ToolKind.PHONE, listOf(AgentTool.Param("text", "string", "text to type"))),
    AgentTool("screen_key", "Press a key: back, home, enter, tab, delete, recents, up, down, left, right, space, search.", ToolKind.PHONE, listOf(AgentTool.Param("key", "string", "key name"))),
    AgentTool("open_app", "Launch an app by package name (e.g. com.google.android.apps.docs).", ToolKind.PHONE, listOf(AgentTool.Param("package", "string", "package name"))),
    AgentTool("adb_shell", "Run an Android shell command with ADB (shell-user) rights: dumpsys, pm, settings, am, screencap, etc. Not a Linux shell — use run_command for the project's Linux tools.", ToolKind.COMMAND, listOf(AgentTool.Param("command", "string", "the shell command"))),
)
