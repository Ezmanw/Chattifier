package com.ethan.localagent.ui.components

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ethan.localagent.data.Attachment
import com.ethan.localagent.data.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AttachmentThumb(a: Attachment, size: Int = 120, onRemove: (() -> Unit)? = null) {
    Box {
        when (a.kind) {
            AttachmentKind.IMAGE, AttachmentKind.VIDEO -> {
                val bmp by produceState<ImageBitmap?>(null, a.path) {
                    value = withContext(Dispatchers.IO) { loadThumb(a) }
                }
                Box(
                    Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    bmp?.let { Image(it, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (a.kind == AttachmentKind.VIDEO) {
                        Icon(Icons.Rounded.PlayCircle, "Video", Modifier.size(36.dp), tint = MaterialTheme.colorScheme.surface)
                    }
                }
            }
            AttachmentKind.AUDIO -> AudioChip(a, size)
        }
        if (onRemove != null) {
            FilledTonalIconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)) {
                Icon(Icons.Rounded.Close, "Remove ${a.name}", Modifier.size(16.dp))
            }
        }
    }
}

private fun loadThumb(a: Attachment): ImageBitmap? = runCatching {
    if (a.kind == AttachmentKind.VIDEO) {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(a.path)
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)?.asImageBitmap()
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(a.path, o)
        val sample = (maxOf(o.outWidth, o.outHeight) / 360).coerceAtLeast(1)
        BitmapFactory.decodeFile(a.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}.getOrNull()

@Composable
private fun AudioChip(a: Attachment, size: Int) {
    var playing by remember { mutableStateOf(false) }
    val player = remember(a.path) { MediaPlayer() }
    DisposableEffect(a.path) { onDispose { player.release() } }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.size(width = size.dp, height = size.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = {
                runCatching {
                    if (playing) { player.pause(); playing = false } else {
                        if (!player.isPlaying && player.currentPosition == 0) {
                            player.reset(); player.setDataSource(a.path); player.prepare()
                            player.setOnCompletionListener { playing = false; it.seekTo(0) }
                        }
                        player.start(); playing = true
                    }
                }
            }, modifier = Modifier.size(36.dp)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(22.dp)) {
                val bars = remember(a.path) { List(10) { (6 + (a.path.hashCode() shr it and 15)).coerceAtMost(22) } }
                bars.forEach { h -> Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.tertiary)) }
            }
            Text("${a.name.take(14)} · ${formatDuration(a.durationMs)}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Just enough Markdown for chat: fenced code blocks, inline code, bold and headings. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val parts = remember(text) { splitFences(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((isCode, body) in parts) {
                if (isCode) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            body.trimEnd(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                            softWrap = false,
                        )
                    }
                } else if (body.isNotBlank()) {
                    Text(inline(body.trim('\n')), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun splitFences(text: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    val segs = text.split("```")
    segs.forEachIndexed { i, s ->
        if (i % 2 == 1) out += true to s.substringAfter('\n', s) else out += false to s
    }
    return out
}

@Composable
private fun inline(s: String): AnnotatedString {
    val code = MaterialTheme.colorScheme.surfaceContainerHigh
    return buildAnnotatedString {
        for (line in s.lines().let { it }) {
            var l = line
            val heading = l.startsWith("#")
            if (heading) l = l.trimStart('#').trim()
            if (l.startsWith("* ") || l.startsWith("- ")) l = "•  " + l.drop(2)
            val start = length
            var i = 0
            while (i < l.length) {
                when {
                    l.startsWith("**", i) -> {
                        val end = l.indexOf("**", i + 2)
                        if (end > 0) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(l.substring(i + 2, end)) }; i = end + 2 } else { append(l[i]); i++ }
                    }
                    l[i] == '`' -> {
                        val end = l.indexOf('`', i + 1)
                        if (end > 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = code)) { append(l.substring(i + 1, end)) }; i = end + 1 } else { append(l[i]); i++ }
                    }
                    else -> { append(l[i]); i++ }
                }
            }
            if (heading) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            append('\n')
        }
        if (length > 0) { /* drop trailing newline */ }
    }.let { if (it.text.endsWith('\n')) it.subSequence(0, it.length - 1) else it }
}
