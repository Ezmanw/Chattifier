package com.ethan.localagent.engine

import android.content.Context
import android.util.Log
import com.ethan.localagent.data.ChatMessage
import com.ethan.localagent.data.ComputeBackend
import com.ethan.localagent.data.EngineKind
import com.ethan.localagent.data.ModelEntry
import com.ethan.localagent.data.ModelRepository
import com.ethan.localagent.data.Prefs
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val model: ModelEntry, val backend: ComputeBackend) : EngineState
    data class Ready(val model: ModelEntry, val backend: ComputeBackend, val note: String? = null) : EngineState
    data class Failed(val model: ModelEntry?, val message: String) : EngineState
}

/**
 * Owns the single loaded model, whichever runtime it needs (LiteRT-LM, llama.cpp, or the
 * speech-to-speech runner). Chat, voice mode and the coding agent share it and take turns
 * holding the one live session; whoever loses the slot gets re-seeded from its own history.
 */
class EngineManager(
    private val context: Context,
    private val prefs: Prefs,
    private val models: ModelRepository,
    private val http: OkHttpClient,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var loadedModel: LoadedModel? = null
    private var loadedKey: Triple<String, ComputeBackend, Int>? = null
    private var session: Session? = null
    private var sessionOwner: Any? = null

    init {
        runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
        ExperimentalFlags.enableBenchmark = true
    }

    val current: ModelEntry? get() = (state.value as? EngineState.Ready)?.model

    suspend fun ensureLoaded(model: ModelEntry? = null): EngineState.Ready = mutex.withLock { ensureLoadedLocked(model) }

    private suspend fun ensureLoadedLocked(modelArg: ModelEntry?): EngineState.Ready {
        val settings = prefs.current()
        val model = modelArg ?: models.byId(settings.activeModelId) ?: models.models.value.firstOrNull()
            ?: throw IllegalStateException("No model installed yet. Get one from the Models tab.")
        val wanted = settings.backend
        val key = Triple(model.id, wanted, settings.contextTokens)
        val st = state.value
        if (loadedModel != null && loadedKey == key && st is EngineState.Ready) return st

        closeLocked()
        val errors = mutableListOf<String>()
        for (backend in fallbackChain(model, wanted)) {
            _state.value = EngineState.Loading(model, backend)
            try {
                loadedModel = withContext(Dispatchers.IO) {
                    when (model.kind) {
                        EngineKind.LITERT -> LiteRtModel.load(context, model, backend, settings.contextTokens)
                        EngineKind.GGUF -> GgufModel.load(context, http, model, backend, settings.contextTokens)
                        EngineKind.SPEECH -> SpeechModel.load(context, http, model)
                    }
                }
                loadedKey = key
                val note = when {
                    backend == wanted || model.npuOnly -> null
                    model.kind == EngineKind.LITERT -> "${wanted.label} isn't available for this model, so it's running on ${backend.label}"
                    else -> "This model runs on ${backend.label}"
                }
                return EngineState.Ready(model, backend, note).also { _state.value = it }
            } catch (t: Throwable) {
                Log.w(TAG, "Loading ${model.name} on $backend failed", t)
                errors += "${backend.label}: ${t.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: t}"
            }
        }
        val msg = "Couldn't load ${model.name}.\n" + errors.joinToString("\n")
        _state.value = EngineState.Failed(model, msg)
        throw IllegalStateException(msg)
    }

    private fun fallbackChain(model: ModelEntry, wanted: ComputeBackend): List<ComputeBackend> {
        if (model.npuOnly) return listOf(ComputeBackend.NPU)
        val first = model.effectiveBackend(wanted)
        return listOf(first, ComputeBackend.GPU, ComputeBackend.CPU).distinct().filter { model.supports(it) }
            .ifEmpty { listOf(ComputeBackend.CPU) }
    }

    /**
     * A live session for [owner]. If someone else held the slot (or [forceNew]), a fresh one is
     * opened and seeded with [history] so the owner picks up where it left off.
     */
    suspend fun sessionFor(
        owner: Any,
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        forceNew: Boolean = false,
        modelId: String? = null,
    ): Session = mutex.withLock {
        val ready = ensureLoadedLocked(modelId?.let { models.byId(it) })
        val existing = session
        if (!forceNew && existing != null && sessionOwner === owner && existing.alive) return@withLock existing
        existing?.runCatching { close() }
        val s = withContext(Dispatchers.IO) {
            loadedModel!!.open(systemPrompt, history, tools, prefs.current().temperature)
        }
        Log.i(TAG, "session for ${owner::class.simpleName} on ${ready.backend}")
        session = s
        sessionOwner = owner
        s
    }

    fun owns(owner: Any): Boolean = sessionOwner === owner && session?.alive == true

    suspend fun resetSession(owner: Any) = mutex.withLock {
        if (sessionOwner === owner) {
            session?.runCatching { close() }
            session = null
            sessionOwner = null
        }
    }

    fun cancel() { session?.cancel() }

    fun stats(): Pair<Float?, ComputeBackend?> = session?.tokensPerSecond() to (state.value as? EngineState.Ready)?.backend

    suspend fun unload() = mutex.withLock { closeLocked(); _state.value = EngineState.Idle }

    private fun closeLocked() {
        session?.runCatching { close() }
        session = null
        sessionOwner = null
        loadedModel?.runCatching { close() }
        loadedModel = null
        loadedKey = null
    }

    companion object {
        private const val TAG = "EngineManager"
    }
}
