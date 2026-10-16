package com.ethan.localagent.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Everything the models need media converted into: JPEG frames and 16 kHz mono WAV. */
object Media {
    const val SAMPLE_RATE = 16_000

    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    suspend fun copyToFile(context: Context, uri: Uri, dest: File): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(dest).use { input.copyTo(it, 1 shl 16) }
        }
        dest
    }

    fun durationMs(file: File): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        }
    }.getOrDefault(0L)

    /** Evenly spaced frames from a video, cached by file identity. */
    suspend fun videoFrames(context: Context, video: File, maxFrames: Int): List<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "frames/${key(video)}").apply { mkdirs() }
        dir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name }?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val out = mutableListOf<File>()
        MediaMetadataRetriever().use { r ->
            r.setDataSource(video.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // About one frame a second for short clips, spread out for longer ones.
            val count = (dur / 1000).toInt().coerceIn(1, maxFrames)
            for (i in 0 until count) {
                val tUs = dur * 1000L * (2 * i + 1) / (2 * count)
                val bmp = r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 768, 768) ?: continue
                val f = File(dir, "f%02d.jpg".format(i))
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle()
                out += f
            }
        }
        out
    }

    suspend fun videoAudioWav(context: Context, video: File): File? = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "frames/${key(video)}/audio.wav")
        if (f.exists()) return@withContext f
        runCatching { decodeToWav(video, f) }.getOrNull()
    }

    /** Decodes any audio Android can read (m4a, mp3, ogg, wav, video audio tracks) to 16 kHz mono WAV. */
    suspend fun decodeToWav(input: File, output: File, maxSeconds: Int = 30 * 60): File = withContext(Dispatchers.IO) {
        resampleCarry = 0.0
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in ${input.name}")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val maxBytes = maxSeconds.toLong() * SAMPLE_RATE * 2
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frame = ShortArray(shorts.remaining()).also { shorts.get(it) }
                        val mono = downmixResample(frame, channels, srcRate)
                        val bb = ByteBuffer.allocate(mono.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        mono.forEach { bb.putShort(it) }
                        pcm.write(bb.array())
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || pcm.size() > maxBytes) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
        }
        writeWav(output, pcm.toByteArray())
        output
    }

    private var resampleCarry = 0.0

    private fun downmixResample(frame: ShortArray, channels: Int, srcRate: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = frame.size / ch
        val mono = FloatArray(frames) { i ->
            var s = 0f
            for (c in 0 until ch) s += frame[i * ch + c]
            s / ch
        }
        if (srcRate == SAMPLE_RATE) return ShortArray(frames) { mono[it].toInt().toShort() }
        val ratio = srcRate.toDouble() / SAMPLE_RATE
        val out = ArrayList<Short>((frames / ratio).toInt() + 2)
        var pos = resampleCarry
        while (pos < frames - 1) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            out += (mono[i] * (1 - frac) + mono[i + 1] * frac).toInt().toShort()
            pos += ratio
        }
        resampleCarry = pos - (frames - 1)
        return out.toShortArray()
    }

    fun writeWav(file: File, pcm16: ByteArray, sampleRate: Int = SAMPLE_RATE) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm16.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm16.size)
            }
            raf.write(header.array())
            raf.write(pcm16)
        }
    }

    private fun key(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        md.update("${file.absolutePath}:${file.length()}:${file.lastModified()}".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
