package com.ethan.localagent.shell

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed interface ShellState {
    data object Unsupported : ShellState
    data object NotInstalled : ShellState
    data class Installing(val step: String, val progress: Float?) : ShellState
    data object Ready : ShellState
    data class Failed(val message: String) : ShellState
}

data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean = false)

/**
 * The built-in Linux userland: Termux's PRoot (shipped as native libs, so Android lets us execute
 * it) running an Alpine Linux root filesystem kept in app storage. Shared storage is bound at the
 * same paths as on Android, so the agent and the user talk about files the same way.
 */
class LinuxShell(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val home = File(context.filesDir, "linux")
    val rootfs = File(home, "rootfs")
    private val tmp = File(home, "tmp")
    private val marker = File(home, ".installed-v1")
    private val libDir = context.applicationInfo.nativeLibraryDir
    private val installLock = Mutex()

    private val _state = MutableStateFlow<ShellState>(initialState())
    val state: StateFlow<ShellState> = _state.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private fun initialState(): ShellState = when {
        Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a" -> ShellState.Unsupported
        marker.exists() -> ShellState.Ready
        else -> ShellState.NotInstalled
    }

    val ready: Boolean get() = _state.value == ShellState.Ready

    fun install() {
        if (_state.value == ShellState.Ready || _state.value is ShellState.Installing || _state.value == ShellState.Unsupported) return
        scope.launch(Dispatchers.IO) {
            installLock.withLock {
                try {
                    _log.value = emptyList()
                    doInstall()
                    marker.writeText(ROOTFS_VERSION)
                    _state.value = ShellState.Ready
                } catch (e: Exception) {
                    Log.w(TAG, "install failed", e)
                    note("error: ${e.message}")
                    _state.value = ShellState.Failed(e.message ?: "Install failed")
                }
            }
        }
    }

    private fun note(line: String) {
        _log.value = (_log.value + line).takeLast(400)
    }

    private suspend fun doInstall() {
        home.mkdirs(); tmp.mkdirs()
        val archive = File(home, "rootfs.tar.gz")
        if (!rootfs.resolve("etc/alpine-release").exists()) {
            _state.value = ShellState.Installing("Downloading Alpine Linux", 0f)
            note("$ download $ROOTFS_URL")
            download(ROOTFS_URL, archive) { f -> _state.value = ShellState.Installing("Downloading Alpine Linux", f * 0.2f) }
            val sum = sha256(archive)
            if (!sum.equals(ROOTFS_SHA256, true)) { archive.delete(); error("Checksum mismatch for the Linux download") }
            note("sha256 ok")
            _state.value = ShellState.Installing("Unpacking", 0.22f)
            rootfs.deleteRecursively(); rootfs.mkdirs()
            extract(archive, rootfs)
            archive.delete()
            note("unpacked")
        }
        writeGuestFiles()
        _state.value = ShellState.Installing("Installing tools", 0.3f)
        val script = "apk update && apk add --no-progress $PACKAGES"
        note("$ $script")
        var total = 0
        val result = run(script, workingDir = "/root", timeoutMs = 30 * 60_000L) { line ->
            note(line)
            // "(12/58) Installing git (2.54.0-r0)"
            Regex("""^\((\d+)/(\d+)\)""").find(line)?.let { m ->
                val (n, t) = m.destructured
                total = t.toInt()
                _state.value = ShellState.Installing("Installing tools · ${line.substringAfter(") ").substringBefore(" (")}", 0.3f + 0.7f * n.toInt() / total)
            }
        }
        if (result.exitCode != 0) error("Installing tools failed (exit ${result.exitCode})")
    }

    private fun writeGuestFiles() {
        rootfs.resolve("etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        rootfs.resolve("etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        rootfs.resolve("etc/profile.d").mkdirs()
        rootfs.resolve("etc/profile.d/localagent.sh").writeText(
            "export PS1='\\w \\$ '\nexport LANG=C.UTF-8\nexport TERM=xterm-256color\n",
        )
        rootfs.resolve("root").mkdirs()
        rootfs.resolve("tmp").mkdirs()
    }

    /** Runs [command] with bash (sh before tools are installed) inside the guest. */
    suspend fun run(
        command: String,
        workingDir: String,
        timeoutMs: Long = 120_000,
        onLine: ((String) -> Unit)? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = start(command, workingDir)
        val out = StringBuilder()
        val reader = launchReader(process, out, onLine)
        val finished = withTimeoutOrNull(timeoutMs) { runInterruptible { process.waitFor() } }
        if (finished == null) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join()
        val text = synchronized(out) { out.toString() }
        CommandResult(if (finished == null) -1 else process.exitValue(), text.takeLast(MAX_OUTPUT), timedOut = finished == null)
    }

    private fun launchReader(process: Process, out: StringBuilder, onLine: ((String) -> Unit)?) = scope.launch(Dispatchers.IO) {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { l ->
                synchronized(out) { if (out.length < MAX_OUTPUT * 2) out.appendLine(l) }
                onLine?.invoke(l)
            }
        }
    }

    private fun start(command: String, workingDir: String): Process {
        val shell = if (rootfs.resolve("bin/bash").exists()) "/bin/bash" else "/bin/sh"
        val argv = mutableListOf(
            "$libDir/libproot.so",
            "-r", rootfs.absolutePath,
            "-0", "--link2symlink", "--kill-on-exit",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "${rootfs.absolutePath}/tmp:/dev/shm",
        )
        // Shared storage at its real Android paths, plus the usual shortcuts.
        if (File("/storage/emulated/0").canRead()) {
            argv += listOf("-b", "/storage", "-b", "/storage/emulated/0:/sdcard", "-b", "/storage/emulated/0:/root/storage/shared")
        }
        argv += listOf(
            "-w", workingDir,
            "/usr/bin/env", "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=dumb", "GIT_PAGER=cat", "PAGER=cat",
            shell, "-lc", command,
        )
        return ProcessBuilder(argv)
            .redirectErrorStream(true)
            .directory(tmp)
            .apply {
                environment()["LD_LIBRARY_PATH"] = libDir
                environment()["PROOT_LOADER"] = "$libDir/libproot_loader.so"
                environment()["PROOT_TMP_DIR"] = tmp.absolutePath
            }
            .start()
            .also { it.outputStream.close() }
    }

    private suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Download failed: HTTP ${r.code}")
            val total = r.body.contentLength()
            var done = 0L
            FileOutputStream(dest).use { out ->
                r.body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
    }

    private fun extract(archive: File, dest: File) {
        val hardlinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val name = e.name.removePrefix("./")
                if (name.isEmpty() || name.split('/').any { it == ".." }) continue
                val target = File(dest, name)
                when {
                    e.isDirectory -> target.mkdirs()
                    e.isSymbolicLink -> {
                        target.parentFile?.mkdirs(); target.delete()
                        Os.symlink(e.linkName, target.absolutePath)
                    }
                    e.isLink -> hardlinks += target to File(dest, e.linkName.removePrefix("./"))
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { tar.copyTo(it) }
                        Os.chmod(target.absolutePath, e.mode and 0x1ff)
                    }
                }
            }
        }
        // Android app storage can't hold hard links; a copy behaves the same for a rootfs.
        for ((link, src) in hardlinks) {
            link.parentFile?.mkdirs(); src.copyTo(link, overwrite = true)
            Os.chmod(link.absolutePath, Os.stat(src.absolutePath).st_mode and 0x1ff)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun reset() {
        scope.launch(Dispatchers.IO) {
            installLock.withLock {
                home.deleteRecursively()
                _state.value = initialState()
            }
        }
    }

    companion object {
        private const val TAG = "LinuxShell"
        private const val MAX_OUTPUT = 16_000
        const val ROOTFS_VERSION = "alpine-3.24.2"
        const val ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz"
        const val ROOTFS_SHA256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
        const val PACKAGES = "bash git ripgrep python3 py3-pip nodejs npm curl coreutils findutils grep sed diffutils patch less file openssh-client"
    }
}
