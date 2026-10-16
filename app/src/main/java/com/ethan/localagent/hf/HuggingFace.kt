package com.ethan.localagent.hf

import com.ethan.localagent.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
private data class ApiSibling(val rfilename: String)

@Serializable
private data class ApiModel(
    val id: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val gated: kotlinx.serialization.json.JsonElement? = null,
    val pipeline_tag: String? = null,
    val tags: List<String> = emptyList(),
    val siblings: List<ApiSibling> = emptyList(),
)

@Serializable
private data class ApiTreeEntry(val type: String, val path: String, val size: Long = 0)

data class HfModel(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val gated: Boolean,
    val files: List<String>,
    val tags: List<String>,
) {
    val author: String get() = id.substringBefore('/')
    val name: String get() = id.substringAfter('/')
    val hasLiteRt: Boolean get() = files.any { it.endsWith(".litertlm", true) }
    val hasGguf: Boolean get() = files.any { it.endsWith(".gguf", true) }
    val looksVision: Boolean get() = tags.any { it in VISION_TAGS } || VISION_HINTS.any { name.contains(it, true) }
    val looksAudio: Boolean get() = tags.any { it in AUDIO_TAGS } || AUDIO_HINTS.any { name.contains(it, true) }

    companion object {
        private val VISION_TAGS = setOf("image-text-to-text", "image-to-text", "visual-question-answering", "video-text-to-text")
        private val AUDIO_TAGS = setOf("automatic-speech-recognition", "audio-text-to-text", "any-to-any")
        private val VISION_HINTS = listOf("vl", "vision", "llava", "gemma-3n", "gemma-4", "smolvlm", "fastvlm", "ocr")
        private val AUDIO_HINTS = listOf("asr", "whisper", "audio", "gemma-3n", "gemma-4", "parakeet", "moonshine", "voxtral")
    }
}

data class HfFile(val path: String, val size: Long) {
    val isModel: Boolean get() = path.endsWith(".litertlm", true) || path.endsWith(".gguf", true)
    val isProjector: Boolean get() = path.contains("mmproj", true)
}

/** A hand-picked starting point for the device this runs on. */
data class Suggestion(
    val repo: String,
    val file: String,
    val title: String,
    val blurb: String,
    val approxBytes: Long,
    val npuSoc: String? = null,
    /** Extra files that come with it (encoders, vocoder…), with their role. */
    val extras: List<BundleFile> = emptyList(),
    val kind: com.ethan.localagent.data.EngineKind? = null,
) {
    fun task() = DownloadTask(repo, listOf(BundleFile(file, approxBytes - extras.sumOf { it.size })) + extras, title, kind)
}

class HuggingFace(private val http: OkHttpClient, private val prefs: Prefs) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, limit: Int = 40): List<HfModel> = coroutineScope {
        // LiteRT-LM files aren't tagged consistently, so search broadly and keep repos with usable files.
        val broad = async { fetchModels(query, limit, filter = null) }
        val gguf = async { fetchModels(query, limit / 2, filter = "gguf") }
        (broad.await() + gguf.await())
            .distinctBy { it.id }
            .filter { it.hasLiteRt || it.hasGguf }
            .sortedWith(compareByDescending<HfModel> { it.hasLiteRt }.thenByDescending { it.downloads })
    }

    private suspend fun fetchModels(query: String, limit: Int, filter: String?): List<HfModel> {
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("full", "true")
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .apply { filter?.let { addQueryParameter("filter", it) } }
            .build()
        val body = get(url.toString())
        return json.decodeFromString<List<ApiModel>>(body).map { m ->
            HfModel(
                id = m.id,
                downloads = m.downloads,
                likes = m.likes,
                gated = m.gated?.toString()?.let { it != "false" } ?: false,
                files = m.siblings.map { it.rfilename },
                tags = m.tags + listOfNotNull(m.pipeline_tag),
            )
        }
    }

    suspend fun files(repo: String): List<HfFile> {
        val body = get("https://huggingface.co/api/models/$repo/tree/main?recursive=1")
        return json.decodeFromString<List<ApiTreeEntry>>(body)
            .filter { it.type == "file" }
            .map { HfFile(it.path, it.size) }
            .filter { it.isModel }
            .sortedBy { it.size }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val token = prefs.current().hfToken
        val req = Request.Builder().url(url).apply {
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Hugging Face returned HTTP ${r.code}")
            r.body.string()
        }
    }

    companion object {
        fun resolveUrl(repo: String, path: String) = "https://huggingface.co/$repo/resolve/main/$path?download=true"

        fun suggestions(soc: String): List<Suggestion> = buildList {
            if (soc.equals("SM8750", true)) {
                add(
                    Suggestion(
                        "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it_qualcomm_sm8750.litertlm",
                        "Gemma 4 E2B · NPU", "Built for your Snapdragon 8 Elite's NPU. Fastest, lowest battery.",
                        3_020_000_000, npuSoc = "SM8750",
                    ),
                )
            }
            add(
                Suggestion(
                    "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
                    "Gemma 4 E2B", "Photos, audio and voice mode. Runs on CPU or GPU.", 2_590_000_000,
                ),
            )
            add(
                Suggestion(
                    "litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm",
                    "Gemma 4 E4B", "Smarter, still sees and hears. Needs about 5 GB of RAM.", 3_660_000_000,
                ),
            )
            add(
                Suggestion(
                    "litert-community/Qwen3-4B", "qwen3_4b_mixed_int4.litertlm",
                    "Qwen3 4B", "Good at tool use. Best pick for Code mode.", 2_600_000_000,
                ),
            )
            add(
                Suggestion(
                    "LiquidAI/LFM2.5-Audio-1.5B-GGUF", "LFM2.5-Audio-1.5B-Q4_0.gguf",
                    "LFM2.5 Audio · speech to speech", "Hears you and talks back in its own voice. Best for Voice mode.",
                    695_750_880 + 219_511_136 + 108_986_560 + 50_546_112,
                    extras = listOf(
                        BundleFile("mmproj-LFM2.5-Audio-1.5B-Q4_0.gguf", 219_511_136, FileRole.PROJECTOR),
                        BundleFile("vocoder-LFM2.5-Audio-1.5B-Q4_0.gguf", 108_986_560, FileRole.VOCODER),
                        BundleFile("tokenizer-LFM2.5-Audio-1.5B-Q4_0.gguf", 50_546_112, FileRole.SPEAKER),
                    ),
                    kind = com.ethan.localagent.data.EngineKind.SPEECH,
                ),
            )
            add(
                Suggestion(
                    "unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                    "Qwen3 4B Instruct · GGUF", "llama.cpp on your Adreno GPU. Great at tool calls for Code mode.", 2_497_280_000,
                ),
            )
        }
    }
}
