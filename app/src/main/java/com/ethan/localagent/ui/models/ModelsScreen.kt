package com.ethan.localagent.ui.models

import android.app.Application
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ethan.localagent.app
import com.ethan.localagent.data.ModelEntry
import com.ethan.localagent.hf.DownloadStatus
import com.ethan.localagent.hf.DownloadTask
import com.ethan.localagent.hf.HfFile
import com.ethan.localagent.hf.HfModel
import com.ethan.localagent.hf.HuggingFace
import com.ethan.localagent.hf.Suggestion
import com.ethan.localagent.util.Media
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<HfModel> = emptyList(),
    val error: String? = null,
    val expanded: String? = null,
    val files: Map<String, List<HfFile>> = emptyMap(),
    val importing: Boolean = false,
)

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.app
    val models = c.models.models
    val downloads = c.downloads.tasks
    private val _search = MutableStateFlow(SearchState())
    val search = _search.asStateFlow()

    fun search(q: String) {
        _search.update { it.copy(query = q, loading = true, error = null, expanded = null) }
        viewModelScope.launch {
            runCatching { c.hf.search(q) }
                .onSuccess { r -> _search.update { it.copy(loading = false, results = r) } }
                .onFailure { e -> _search.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun toggle(repo: String) {
        val open = _search.value.expanded != repo
        _search.update { it.copy(expanded = if (open) repo else null) }
        if (open && repo !in _search.value.files) viewModelScope.launch {
            runCatching { c.hf.files(repo) }.onSuccess { f -> _search.update { it.copy(files = it.files + (repo to f)) } }
                .onFailure { e -> _search.update { it.copy(error = e.message) } }
        }
    }

    /** GGUF files come with their vision/audio encoder ("mmproj") from the same repo when there is one. */
    fun get(repo: String, file: HfFile) {
        val title = com.ethan.localagent.data.ModelRepository.prettyName(file.path.substringAfterLast('/'))
        val files = mutableListOf(com.ethan.localagent.hf.BundleFile(file.path, file.size))
        if (file.path.endsWith(".gguf", true)) {
            val projectors = _search.value.files[repo].orEmpty().filter { it.isProjector }
            val pick = projectors.firstOrNull { it.path.contains("f16", true) } ?: projectors.firstOrNull { it.path.contains("q8", true) } ?: projectors.minByOrNull { it.size }
            pick?.let { files += com.ethan.localagent.hf.BundleFile(it.path, it.size, com.ethan.localagent.hf.FileRole.PROJECTOR) }
        }
        c.downloads.enqueue(com.ethan.localagent.hf.DownloadTask(repo, files, title))
    }

    fun get(s: Suggestion) = c.downloads.enqueue(s.task())
    fun pause(t: DownloadTask) = c.downloads.pause(t.key)
    fun resume(t: DownloadTask) = c.downloads.resume(t.key)
    fun cancel(t: DownloadTask) = c.downloads.cancel(t.key)
    fun dismiss(t: DownloadTask) = c.downloads.dismiss(t.key)
    fun delete(m: ModelEntry) = viewModelScope.launch {
        if (c.engine.current?.id == m.id) c.engine.unload()
        c.models.remove(m.id)
    }
    fun use(m: ModelEntry) = viewModelScope.launch {
        c.prefs.update { it.copy(activeModelId = m.id) }
        // Load it right away, rather than lazily on the first message.
        runCatching { c.engine.ensureLoaded(m) }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        _search.update { it.copy(importing = true) }
        runCatching {
            val ctx = getApplication<Application>()
            val name = Media.displayName(ctx, uri)
            require(name.endsWith(".litertlm", true) || name.endsWith(".gguf", true)) { "Pick a .litertlm or .gguf file" }
            val f = Media.copyToFile(ctx, uri, File(c.models.dir, name))
            c.models.add(c.models.inspect(f, null))
        }.onFailure { e -> _search.update { it.copy(error = e.message) } }
        _search.update { it.copy(importing = false) }
    }

    fun saveToken(t: String) = viewModelScope.launch { c.prefs.update { it.copy(hfToken = t.trim()) } }
}

@Composable
fun ModelsScreen(vm: ModelsViewModel = viewModel()) {
    val ctx = LocalContext.current
    val models by vm.models.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val settings by ctx.app.prefs.settings.collectAsStateWithLifecycle(null)
    var tab by rememberSaveable { mutableStateOf(if (models.isEmpty()) 1 else 0) }
    var tokenDialog by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }

    LaunchedEffect(Unit) { if (search.results.isEmpty() && search.query.isEmpty()) vm.search("litert-community") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                actions = {
                    IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.FileOpen, "Import a model file") }
                    IconButton(onClick = { tokenDialog = true }) { Icon(Icons.Rounded.Key, "Hugging Face token") }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("On device · ${models.size}") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Hugging Face") })
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                val live = downloads.filter { it.status !is DownloadStatus.Done }
                items(live, key = { "dl:" + it.key }) { DownloadCard(it, vm) }
                if (search.importing) item { Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(); Text("Importing…") } }
                search.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }

                if (tab == 0) {
                    if (models.isEmpty()) item {
                        Text("Nothing downloaded yet. Pick one from Hugging Face.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(models, key = { it.id }) { m -> InstalledCard(m, active = m.id == settings?.activeModelId, onUse = { vm.use(m) }, onDelete = { vm.delete(m) }) }
                } else {
                    item { SearchField(search.query, onSearch = vm::search) }
                    if (search.query.isBlank() || search.query == "litert-community") {
                        item { Text("Picked for your phone", style = MaterialTheme.typography.titleMedium) }
                        items(HuggingFace.suggestions(Build.SOC_MODEL), key = { "s:" + it.file }) { s ->
                            SuggestionCard(s, installed = models.any { it.fileName.endsWith(s.file) }, downloading = downloads.any { it.path == s.file && it.active }, onGet = { vm.get(s) })
                        }
                        item { Text("Everything else", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    }
                    if (search.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LoadingIndicator() } }
                    items(search.results, key = { "r:" + it.id }) { r ->
                        ResultCard(r, expanded = search.expanded == r.id, files = search.files[r.id], onToggle = { vm.toggle(r.id) }, onGet = { f -> vm.get(r.id, f) })
                    }
                }
            }
        }
    }

    if (tokenDialog) {
        var token by remember { mutableStateOf(settings?.hfToken.orEmpty()) }
        AlertDialog(
            onDismissRequest = { tokenDialog = false },
            title = { Text("Hugging Face token") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Only needed for gated models. Create a read token at huggingface.co/settings/tokens. It stays on this phone.")
                    OutlinedTextField(token, { token = it }, label = { Text("hf_…") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveToken(token); tokenDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { tokenDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SearchField(initial: String, onSearch: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf(if (initial == "litert-community") "" else initial) }
    OutlinedTextField(
        value = q, onValueChange = { q = it },
        placeholder = { Text("Search Hugging Face") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = ""; onSearch("litert-community") }) { Icon(Icons.Rounded.Close, "Clear") } },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onSearch(q) }),
        colors = SearchBarDefaults.inputFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DownloadCard(t: DownloadTask, vm: ModelsViewModel) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.repo, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (t.status) {
                    is DownloadStatus.Running, DownloadStatus.Queued -> IconButton(onClick = { vm.pause(t) }) { Icon(Icons.Rounded.Pause, "Pause") }
                    is DownloadStatus.Paused -> IconButton(onClick = { vm.resume(t) }) { Icon(Icons.Rounded.PlayArrow, "Resume") }
                    is DownloadStatus.Failed -> IconButton(onClick = { vm.resume(t) }) { Icon(Icons.Rounded.Refresh, "Retry") }
                    else -> {}
                }
                if (t.status !is DownloadStatus.Installing) IconButton(onClick = { vm.cancel(t) }) { Icon(Icons.Rounded.Close, "Cancel") }
            }
            when (val s = t.status) {
                is DownloadStatus.Running -> {
                    val frac = if (s.total > 0) s.bytes.toFloat() / s.total else 0f
                    LinearWavyProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${Formatter.formatShortFileSize(ctx, s.bytes)} of ${Formatter.formatShortFileSize(ctx, s.total)} · ${Formatter.formatShortFileSize(ctx, s.bytesPerSecond)}/s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DownloadStatus.Queued -> { LinearWavyProgressIndicator(Modifier.fillMaxWidth()); Text("Starting…", style = MaterialTheme.typography.bodySmall) }
                DownloadStatus.Installing -> { LinearWavyProgressIndicator(Modifier.fillMaxWidth()); Text("Checking what it can do…", style = MaterialTheme.typography.bodySmall) }
                is DownloadStatus.Paused -> Text("Paused at ${Formatter.formatShortFileSize(ctx, s.bytes)}", style = MaterialTheme.typography.bodySmall)
                is DownloadStatus.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                DownloadStatus.Done -> {}
            }
        }
    }
}

@Composable
private fun InstalledCard(m: ModelEntry, active: Boolean, onUse: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Text("${m.kind.name.lowercase()} · ${Formatter.formatShortFileSize(ctx, m.sizeBytes)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if (active) Icon(Icons.Rounded.CheckCircle, "In use", tint = MaterialTheme.colorScheme.primary)
                else FilledTonalButton(onClick = onUse) { Text("Use") }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete ${m.name}") }
            }
            CapabilityRow(vision = m.vision, audio = m.audio, npu = m.npuSoc, audioOnly = m.audioOnly)
        }
    }
}

@Composable
private fun CapabilityRow(vision: Boolean, audio: Boolean, npu: String?, audioOnly: Boolean = false, gated: Boolean = false) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (vision) item { AssistChip(onClick = {}, label = { Text("Image") }, leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(16.dp)) }) }
        if (audio) item { AssistChip(onClick = {}, label = { Text(if (audioOnly) "Audio only" else "Audio · voice mode") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(16.dp)) }) }
        if (npu != null) item { AssistChip(onClick = {}, label = { Text("NPU · $npu") }, leadingIcon = { Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp)) }) }
        if (gated) item { AssistChip(onClick = {}, label = { Text("Gated · needs token") }, leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)) }) }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, installed: Boolean, downloading: Boolean, onGet: () -> Unit) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium)
                Text(s.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("~${Formatter.formatShortFileSize(ctx, s.approxBytes)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.size(12.dp))
            when {
                installed -> Icon(Icons.Rounded.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
                downloading -> LoadingIndicator(Modifier.size(36.dp))
                else -> Button(onClick = onGet) { Text("Get") }
            }
        }
    }
}

@Composable
private fun ResultCard(r: HfModel, expanded: Boolean, files: List<HfFile>?, onToggle: () -> Unit, onGet: (HfFile) -> Unit) {
    val ctx = LocalContext.current
    Card(onClick = onToggle, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${r.author} · ${if (r.hasLiteRt) "LiteRT" else "GGUF"} · ${r.downloads} downloads",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CapabilityRow(vision = r.looksVision, audio = r.looksAudio, npu = null, gated = r.gated)
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (files == null) LoadingIndicator(Modifier.size(32.dp))
                    val hasProjector = files?.any { it.isProjector } == true
                    files?.filterNot { it.isProjector }?.forEach { f ->
                        val ggufNote = if (f.path.endsWith(".gguf", true)) " · llama.cpp" + (if (hasProjector) " + image/audio encoder" else "") else ""
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Formatter.formatShortFileSize(ctx, f.size) + ggufNote + (ModelRepositorySoc(f.path)?.let { " · NPU $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onGet(f) }) { Text("Get") }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionName")
private fun ModelRepositorySoc(path: String) = com.ethan.localagent.data.ModelRepository.socFromFileName(path)
