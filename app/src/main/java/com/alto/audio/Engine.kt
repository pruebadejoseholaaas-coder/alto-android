package com.alto.audio

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

const val BLOCK = 1024
val LE: ByteOrder = ByteOrder.LITTLE_ENDIAN

/** Audio ya decodificado a PCM de 16 bits guardado en un archivo (no en memoria). */
class Clip(val file: File, val sr: Int, val ch: Int, val frames: Long, val peaks: FloatArray, val peak: Float)

fun shape(v0: Float, g: Float, lim: Boolean): Short {
    var v = v0 / 32768f * g
    if (lim) {
        val a = abs(v)
        if (a > 0.9f) v = sign(v) * (0.9f + 0.1f * tanh((a - 0.9f) / 0.1f))
    }
    return (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}

class PeakAcc(private val ch: Int) {
    val list = ArrayList<Float>()
    var g = 0f
    private var m = 0f
    private var n = 0
    fun add(s: Short) {
        val a = abs(s.toInt()) / 32768f
        if (a > m) m = a
        if (++n >= BLOCK * ch) flush()
    }
    fun flush() { if (n > 0) { list.add(m); if (m > g) g = m; m = 0f; n = 0 } }
}

fun decode(ctx: Context, uri: Uri, out: File, onProgress: (Float) -> Unit): Clip {
    val ex = MediaExtractor()
    ex.setDataSource(ctx, uri, null)
    var idx = -1
    var fmt: MediaFormat? = null
    for (i in 0 until ex.trackCount) {
        val f = ex.getTrackFormat(i)
        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
    }
    if (idx < 0 || fmt == null) throw IOException("Este archivo no tiene audio que Android pueda leer.")
    ex.selectTrack(idx)
    val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
    val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(fmt, null, null, 0)
    codec.start()
    var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    var acc = PeakAcc(ch)
    val info = MediaCodec.BufferInfo()
    var inDone = false
    var outDone = false
    var frames = 0L
    FileOutputStream(out).buffered(1 shl 16).use { os ->
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val b = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(b, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                acc.flush(); acc = PeakAcc(ch).also { it.list.addAll(acc.list); it.g = acc.g }
            } else if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!
                b.position(info.offset); b.limit(info.offset + info.size)
                val sb = b.order(LE).asShortBuffer()
                val arr = ShortArray(sb.remaining())
                sb.get(arr)
                val bb = ByteBuffer.allocate(arr.size * 2).order(LE)
                bb.asShortBuffer().put(arr)
                os.write(bb.array())
                for (s in arr) acc.add(s)
                frames += arr.size / ch
                if (durUs > 0) onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                codec.releaseOutputBuffer(o, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
            }
        }
    }
    acc.flush()
    codec.stop(); codec.release(); ex.release()
    return Clip(out, sr, ch, frames, acc.list.toFloatArray(), acc.g)
}

fun scan(f: File, sr: Int, ch: Int): Clip {
    val acc = PeakAcc(ch)
    val buf = ByteArray(1 shl 16)
    var total = 0L
    FileInputStream(f).use { ins ->
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            val sb = ByteBuffer.wrap(buf, 0, n).order(LE).asShortBuffer()
            while (sb.hasRemaining()) acc.add(sb.get())
            total += n
        }
    }
    acc.flush()
    return Clip(f, sr, ch, total / 2 / ch, acc.list.toFloatArray(), acc.g)
}

fun copyFrames(src: Clip, dst: File, from: Long, to: Long, append: Boolean) {
    val bpf = src.ch * 2
    var left = (to - from) * bpf
    val buf = ByteArray(1 shl 16)
    FileInputStream(src.file).use { ins ->
        ins.channel.position(from * bpf)
        FileOutputStream(dst, append).use { os ->
            while (left > 0) {
                val n = ins.read(buf, 0, min(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                os.write(buf, 0, n); left -= n
            }
        }
    }
}

fun trimClip(c: Clip, a: Long, b: Long, out: File): Clip {
    out.delete(); copyFrames(c, out, a, b, false); return scan(out, c.sr, c.ch)
}

fun cutClip(c: Clip, a: Long, b: Long, out: File): Clip {
    out.delete(); copyFrames(c, out, 0, a, false); copyFrames(c, out, b, c.frames, true); return scan(out, c.sr, c.ch)
}

class Player(
    private val c: Clip, private val gain: () -> Float, private val lim: () -> Boolean,
    private val onPos: (Long) -> Unit, private val onEnd: () -> Unit
) {
    @Volatile private var run = false
    private var th: Thread? = null

    fun start(from: Long, to: Long) {
        stop(); run = true
        th = Thread {
            val mask = if (c.ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val mb = AudioTrack.getMinBufferSize(c.sr, mask, AudioFormat.ENCODING_PCM_16BIT)
            val tr = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(c.sr).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(mb, 32768)).setTransferMode(AudioTrack.MODE_STREAM).build()
            tr.play()
            val bpf = c.ch * 2
            var pos = from
            val bytes = ByteArray(bpf * 4096)
            val sh = ShortArray(bytes.size / 2)
            FileInputStream(c.file).use { ins ->
                ins.channel.position(from * bpf)
                while (run && pos < to) {
                    val n = ins.read(bytes, 0, min((to - pos) * bpf, bytes.size.toLong()).toInt())
                    if (n <= 0) break
                    val cnt = n / 2
                    ByteBuffer.wrap(bytes, 0, n).order(LE).asShortBuffer().get(sh, 0, cnt)
                    val g = gain(); val l = lim()
                    for (i in 0 until cnt) sh[i] = shape(sh[i].toFloat(), g, l)
                    tr.write(sh, 0, cnt)
                    pos += n / bpf
                    onPos(pos)
                }
            }
            val finished = run
            if (finished) { tr.stop(); Thread.sleep(250) } else { tr.pause(); tr.flush() }
            tr.release()
            run = false
            if (finished) onEnd()
        }.also { it.start() }
    }

    fun stop() { run = false; th?.join(600); th = null }
}

fun stream(c: Clip, g: Float, lim: Boolean, prog: (Float) -> Unit, size: Int = 1 shl 16, sink: (ByteArray, Int) -> Unit) {
    val buf = ByteArray(size)
    var done = 0L
    val total = max(1L, c.frames * c.ch * 2)
    FileInputStream(c.file).use { ins ->
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            val bb = ByteBuffer.wrap(buf, 0, n).order(LE)
            for (i in 0 until n / 2) bb.putShort(i * 2, shape(bb.getShort(i * 2).toFloat(), g, lim))
            sink(buf, n)
            done += n
            prog(done.toFloat() / total)
        }
    }
}

fun newOut(ctx: Context, name: String, mime: String): Uri {
    val v = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
        put(MediaStore.Audio.Media.MIME_TYPE, mime)
        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Alto")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    return ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)!!
}

fun finish(ctx: Context, u: Uri) {
    ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
}

fun exportWav(ctx: Context, c: Clip, g: Float, lim: Boolean, name: String, prog: (Float) -> Unit) {
    val u = newOut(ctx, "$name.wav", "audio/x-wav")
    val dataLen = (c.frames * c.ch * 2).toInt()
    ctx.contentResolver.openOutputStream(u)!!.buffered().use { os ->
        val h = ByteBuffer.allocate(44).order(LE)
        h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVEfmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(c.ch.toShort()); h.putInt(c.sr)
        h.putInt(c.sr * c.ch * 2); h.putShort((c.ch * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataLen)
        os.write(h.array())
        stream(c, g, lim, prog) { b, n -> os.write(b, 0, n) }
    }
    finish(ctx, u)
}

fun exportM4a(ctx: Context, c: Clip, g: Float, lim: Boolean, name: String, prog: (Float) -> Unit) {
    val u = newOut(ctx, "$name.m4a", "audio/mp4")
    val pfd = ctx.contentResolver.openFileDescriptor(u, "w")!!
    val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, c.sr, c.ch).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 192000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val info = MediaCodec.BufferInfo()
    var track = -1
    var started = false
    var frames = 0L

    fun drain(end: Boolean) {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (end) 10000 else 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(codec.outputFormat); mux.start(); started = true
            } else if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (info.size > 0 && started) {
                    b.position(info.offset); b.limit(info.offset + info.size)
                    mux.writeSampleData(track, b, info)
                }
                codec.releaseOutputBuffer(o, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            } else if (!end) return
        }
    }

    fun nextInput(): Int {
        var i = -1
        while (i < 0) { i = codec.dequeueInputBuffer(5000); if (i < 0) drain(false) }
        return i
    }

    stream(c, g, lim, prog, 16384) { b, n ->
        val i = nextInput()
        val ib = codec.getInputBuffer(i)!!
        ib.clear(); ib.put(b, 0, n)
        codec.queueInputBuffer(i, 0, n, frames * 1_000_000L / c.sr, 0)
        frames += n / (c.ch * 2)
        drain(false)
    }
    val i = nextInput()
    codec.queueInputBuffer(i, 0, 0, frames * 1_000_000L / c.sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    drain(true)
    codec.stop(); codec.release()
    if (started) mux.stop()
    mux.release(); pfd.close()
    finish(ctx, u)
}
