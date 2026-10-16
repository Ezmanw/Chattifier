package com.ethan.localagent.hf

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ethan.localagent.data.EngineKind
import com.ethan.localagent.data.ModelEntry
import com.ethan.localagent.data.ModelRepository
import com.ethan.localagent.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

sealed interface DownloadStatus {
    data object Queued : DownloadStatus
    data class Running(val bytes: Long, val total: Long, val bytesPerSecond: Long) : DownloadStatus
    data class Paused(val bytes: Long, val total: Long) : DownloadStatus
    data class Failed(val message: String) : DownloadStatus
    data object Installing : DownloadStatus
    data object Done : DownloadStatus
}

enum class FileRole { MAIN, PROJECTOR, VOCODER, SPEAKER }

data class BundleFile(val path: String, val size: Long, val role: FileRole = FileRole.MAIN)

/** One model to fetch: usually a single file, or a bundle (GGUF + encoder, speech model parts). */
data class DownloadTask(
    val repo: String,
    val files: List<BundleFile>,
    val title: String,
    val kind: EngineKind? = null,
    val status: DownloadStatus = DownloadStatus.Queued,
) {
    val main: BundleFile get() = files.first { it.role == FileRole.MAIN }
    val path: String get() = main.path
    val key: String get() = "$repo/${main.path}"
    val expectedSize: Long get() = files.sumOf { it.size }
    val active: Boolean get() = status is DownloadStatus.Queued || status is DownloadStatus.Running || status is DownloadStatus.Installing
}

/** Resumable model downloads. Partial files survive app restarts and pick up where they stopped. */
class DownloadManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val prefs: Prefs,
    private val models: ModelRepository,
    private val scope: CoroutineScope,
) {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun enqueue(repo: String, path: String, title: String, size: Long) =
        enqueue(DownloadTask(repo, listOf(BundleFile(path, size)), title))

    fun enqueue(task: DownloadTask) {
        _tasks.update { list -> list.filterNot { it.key == task.key } + task }
        start(task.key)
    }

    fun pause(key: String) {
        jobs.remove(key)?.cancel()
        set(key) { t ->
            val r = t.status as? DownloadStatus.Running
            t.copy(status = DownloadStatus.Paused(r?.bytes ?: 0, r?.total ?: t.expectedSize))
        }
    }

    fun resume(key: String) {
        set(key) { it.copy(status = DownloadStatus.Queued) }
        start(key)
    }

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        _tasks.value.firstOrNull { it.key == key }?.let { t -> t.files.forEach { partFile(t, it).delete() } }
        _tasks.update { list -> list.filterNot { it.key == key } }
    }

    fun dismiss(key: String) = _tasks.update { list -> list.filterNot { it.key == key } }

    private fun start(key: String) {
        if (jobs[key]?.isActive == true) return
        context.startForegroundService(Intent(context, DownloadService::class.java))
        jobs[key] = scope.launch(Dispatchers.IO) {
            val task = _tasks.value.firstOrNull { it.key == key } ?: return@launch
            try {
                val done = mutableMapOf<FileRole, File>()
                var before = 0L
                for (f in task.files) {
                    done[f.role] = download(task, f, before)
                    before += done[f.role]!!.length()
                }
                set(key) { it.copy(status = DownloadStatus.Installing) }
                val entry = register(task, done)
                models.add(entry)
                prefs.update { s -> if (s.activeModelId == null) s.copy(activeModelId = entry.id) else s }
                set(key) { it.copy(status = DownloadStatus.Done) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download ${task.key} failed", e)
                set(key) { it.copy(status = DownloadStatus.Failed(e.message ?: "Download failed")) }
            } finally {
                jobs.remove(key)
            }
        }
    }

    private suspend fun register(task: DownloadTask, files: Map<FileRole, File>): ModelEntry {
        val main = files.getValue(FileRole.MAIN)
        val kind = task.kind ?: if (main.name.endsWith(".gguf", true)) EngineKind.GGUF else EngineKind.LITERT
        return when (kind) {
            EngineKind.LITERT -> models.inspect(main, task.repo, task.title)
            EngineKind.GGUF -> {
                val hasProjector = files[FileRole.PROJECTOR] != null
                val hint = (task.repo + task.title).lowercase()
                ModelEntry(
                    id = "${task.repo}/${main.name}", name = task.title, repo = task.repo, fileName = main.name, path = main.absolutePath,
                    sizeBytes = files.values.sumOf { it.length() }, kind = EngineKind.GGUF,
                    vision = hasProjector && VISION_HINTS.any { it in hint },
                    audio = hasProjector && AUDIO_HINTS.any { it in hint },
                    video = hasProjector && VISION_HINTS.any { it in hint },
                    backends = listOf(com.ethan.localagent.data.ComputeBackend.CPU, com.ethan.localagent.data.ComputeBackend.GPU),
                    projectorPath = files[FileRole.PROJECTOR]?.absolutePath,
                )
            }
            EngineKind.SPEECH -> ModelEntry(
                id = "${task.repo}/${main.name}", name = task.title, repo = task.repo, fileName = main.name, path = main.absolutePath,
                sizeBytes = files.values.sumOf { it.length() }, kind = EngineKind.SPEECH,
                text = true, audio = true,
                backends = listOf(com.ethan.localagent.data.ComputeBackend.CPU),
                projectorPath = files[FileRole.PROJECTOR]?.absolutePath,
                vocoderPath = files[FileRole.VOCODER]?.absolutePath,
                speakerPath = files[FileRole.SPEAKER]?.absolutePath,
            )
        }
    }

    private fun partFile(task: DownloadTask, f: BundleFile) = File(models.dir, safeName(task, f) + ".part")
    private fun finalFile(task: DownloadTask, f: BundleFile) = File(models.dir, safeName(task, f))
    private fun safeName(task: DownloadTask, f: BundleFile) =
        (task.repo.substringAfter('/') + "__" + f.path.substringAfterLast('/')).replace(Regex("[^A-Za-z0-9._-]"), "_")

    private suspend fun download(task: DownloadTask, f: BundleFile, before: Long): File {
        val dest = finalFile(task, f)
        if (dest.exists() && (f.size <= 0 || dest.length() == f.size)) return dest
        val part = partFile(task, f)
        var attempts = 0
        while (true) {
            try {
                fetch(task, f, part, before)
                if (!part.renameTo(dest)) throw IOException("Couldn't move the finished download into place")
                return dest
            } catch (e: GatedException) {
                throw e
            } catch (e: IOException) {
                attempts++
                if (attempts >= 6) throw IOException("Download kept failing: ${e.message}", e)
                Log.w(TAG, "attempt $attempts for ${f.path}: ${e.message}")
                kotlinx.coroutines.delay(2_000L * attempts)
            }
        }
    }

    private class GatedException(msg: String) : IOException(msg)

    private suspend fun fetch(task: DownloadTask, f: BundleFile, part: File, before: Long) {
        val existing = if (part.exists()) part.length() else 0L
        val token = prefs.current().hfToken
        val req = Request.Builder().url(HuggingFace.resolveUrl(task.repo, f.path)).apply {
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) {
                throw GatedException("This model is gated. Accept its licence on huggingface.co, then add your token in Models › key.")
            }
            if (r.code == 416) return
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val resuming = r.code == 206
            val body = r.body
            val fileTotal = if (resuming) existing + body.contentLength() else body.contentLength()
            val grandTotal = task.files.sumOf { it.size }.takeIf { it > 0 } ?: (before + fileTotal)
            var done = if (resuming) existing else 0L
            var windowStart = System.nanoTime()
            var windowBytes = 0L
            var speed = 0L
            FileOutputStream(part, resuming).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 18)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        windowBytes += n
                        val now = System.nanoTime()
                        if (now - windowStart > 1_000_000_000L) {
                            speed = windowBytes * 1_000_000_000L / (now - windowStart)
                            windowStart = now; windowBytes = 0
                        }
                        if (done - lastReport > (1 shl 20)) {
                            lastReport = done
                            set(task.key) { it.copy(status = DownloadStatus.Running(before + done, grandTotal, speed)) }
                        }
                    }
                }
            }
        }
    }

    private fun set(key: String, f: (DownloadTask) -> DownloadTask) =
        _tasks.update { list -> list.map { if (it.key == key) f(it) else it } }

    companion object {
        private const val TAG = "Downloads"
        private val VISION_HINTS = listOf("vl", "vision", "llava", "gemma-3", "gemma-4", "smolvlm", "qwen2.5-omni", "minicpm", "pixtral", "ocr", "lfm2-vl", "lfm2.5-vl")
        private val AUDIO_HINTS = listOf("audio", "omni", "voxtral", "ultravox", "gemma-3n", "gemma-4")
    }
}
