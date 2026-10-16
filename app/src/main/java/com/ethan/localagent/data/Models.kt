package com.ethan.localagent.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.BackendType
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.Modality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class EngineKind {
    /** Google LiteRT-LM (.litertlm): CPU, GPU, NPU. */
    LITERT,
    /** llama.cpp (.gguf): CPU, or GPU through OpenCL. */
    GGUF,
    /** Speech-to-speech bundle (LFM2.5-Audio): hears and talks back in its own voice. */
    SPEECH,
}

@Serializable
data class ModelEntry(
    val id: String,
    val name: String,
    val repo: String? = null,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val kind: EngineKind = EngineKind.LITERT,
    val text: Boolean = true,
    val vision: Boolean = false,
    val audio: Boolean = false,
    val video: Boolean = false,
    /** Backends the file was compiled for, as reported by the model itself. */
    val backends: List<ComputeBackend> = listOf(ComputeBackend.CPU, ComputeBackend.GPU),
    /** SoC an NPU build targets, e.g. "SM8750". Null when the file has no NPU section. */
    val npuSoc: String? = null,
    /** Vision/audio encoder for GGUF and speech models (llama.cpp "mmproj"). */
    val projectorPath: String? = null,
    /** Speech models only: the vocoder and speaker tokenizer that make its voice. */
    val vocoderPath: String? = null,
    val speakerPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    /** Audio in, but no text-generation use beyond transcription (ASR models). */
    val audioOnly: Boolean
        get() = audio && ASR_HINTS.any { fileName.contains(it, ignoreCase = true) }

    /** NPU builds are compiled ahead of time for one chip and can't run anywhere else. */
    val npuOnly: Boolean get() = npuSoc != null

    fun supports(backend: ComputeBackend): Boolean = when {
        kind == EngineKind.SPEECH -> backend == ComputeBackend.CPU
        kind == EngineKind.GGUF -> backend != ComputeBackend.NPU
        backend == ComputeBackend.NPU -> npuSoc != null && npuSoc.equals(Build.SOC_MODEL, ignoreCase = true)
        else -> !npuOnly && backend in backends
    }

    val speaksForItself: Boolean get() = kind == EngineKind.SPEECH

    /** What to actually run on when the user prefers [wanted]. */
    fun effectiveBackend(wanted: ComputeBackend): ComputeBackend = when {
        npuOnly -> ComputeBackend.NPU
        supports(wanted) -> wanted
        supports(ComputeBackend.GPU) -> ComputeBackend.GPU
        else -> ComputeBackend.CPU
    }

    companion object {
        private val ASR_HINTS = listOf("asr", "whisper", "parakeet", "moonshine")
    }
}

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

/** Installed models, persisted as a small JSON index next to the model files. */
class ModelRepository(context: Context, private val scope: CoroutineScope) {
    val dir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val mutex = Mutex()
    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { json.decodeFromString<List<ModelEntry>>(index.readText()) }.getOrDefault(emptyList())
            _models.value = loaded.filter { File(it.path).exists() }
        }
    }

    fun byId(id: String?): ModelEntry? = _models.value.firstOrNull { it.id == id }

    /** Deletes a model and every file it brought along. */
    private fun files(e: ModelEntry) = listOfNotNull(e.path, e.projectorPath, e.vocoderPath, e.speakerPath).map(::File)

    suspend fun add(entry: ModelEntry) = mutex.withLock {
        _models.update { list -> list.filterNot { it.id == entry.id } + entry }
        save()
    }

    suspend fun remove(id: String) = mutex.withLock {
        val entry = byId(id) ?: return@withLock
        withContext(Dispatchers.IO) { files(entry).forEach { it.delete() } }
        _models.update { list -> list.filterNot { it.id == id } }
        save()
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        index.writeText(json.encodeToString(_models.value))
    }

    /** Builds an entry for a finished file, asking LiteRT-LM what the model can take in. */
    suspend fun inspect(file: File, repo: String?, displayName: String? = null): ModelEntry = withContext(Dispatchers.IO) {
        val kind = if (file.name.endsWith(".gguf", true)) EngineKind.GGUF else EngineKind.LITERT
        val base = ModelEntry(
            id = (repo?.let { "$it/" } ?: "local/") + file.name,
            name = displayName ?: prettyName(file.name),
            repo = repo,
            fileName = file.name,
            path = file.absolutePath,
            sizeBytes = file.length(),
            kind = kind,
        )
        if (kind != EngineKind.LITERT) return@withContext base
        runCatching {
            ModelInfo.from(file.absolutePath).use { info ->
                val m = info.inputModalities()
                val textBackends = runCatching { info.supportedBackends(Modality.TEXT) }.getOrDefault(emptyList())
                val backends = textBackends.mapNotNull {
                    when (it) {
                        BackendType.CPU -> ComputeBackend.CPU
                        BackendType.GPU -> ComputeBackend.GPU
                        BackendType.NPU -> ComputeBackend.NPU
                        else -> null
                    }
                }.ifEmpty { listOf(ComputeBackend.CPU, ComputeBackend.GPU) }
                val soc = if (ComputeBackend.NPU in backends) {
                    runCatching { info.socName(Modality.TEXT) }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: socFromFileName(file.name)
                } else socFromFileName(file.name)
                base.copy(
                    text = m.text, vision = m.vision, audio = m.audio, video = m.video || m.vision,
                    backends = backends, npuSoc = soc,
                )
            }
        }.getOrElse {
            Log.w("ModelRepository", "ModelInfo failed for ${file.name}", it)
            base.copy(npuSoc = socFromFileName(file.name))
        }
    }

    companion object {
        fun prettyName(file: String): String = file
            .substringBeforeLast('.')
            .replace(Regex("[_-]it(?=[_-]|$)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[_-](qualcomm|google|intel|mediatek)[_-].*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[_-]gpu$", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ').replace('-', ' ')
            .trim()
            .replaceFirstChar { it.uppercase() }

        fun socFromFileName(name: String): String? =
            Regex("(sm\\d{4}|qcs\\d{4})", RegexOption.IGNORE_CASE).find(name)?.value?.uppercase()
    }
}
