package dev.steven.s3control.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class BellConvertException(message: String) : Exception(message)

/**
 * Turns any audio file into the bike's custom bell file, matching mooovy's pipeline:
 * WAV, PCM s16le, mono, sample rate picked so the file stays under 400 kB,
 * wrapped in the 28-byte VM_SOUND header.
 */
object BellConverter {
    const val MAX_FILE_BYTES = 400_000
    const val MAX_SECONDS = 12f
    /**
     * The bike's amplifier wakes on the first sample, so a bell that starts at once loses its start when
     * pressed on the bike. Every bell keeps a silent lead-in: what the file had, but at least / at most this.
     */
    const val LEAD_IN_MIN_SECONDS = 0.4f
    const val LEAD_IN_MAX_SECONDS = 0.8f
    private val SAMPLE_RATES = intArrayOf(44100, 22050, 16000, 11025, 8000, 6000)
    private val VM_SOUND_HEADER = hexBytes("564D5F534F554E44FFFFFFFF015858585800585858585858")

    class Result(val file: ByteArray, val sampleRate: Int, val seconds: Float, val trimmed: Boolean)

    /** [keepLevel]: leave the samples as recorded (for a bell that is clipped on purpose); no normalising. */
    suspend fun convert(context: Context, uri: Uri, keepLevel: Boolean = false): Result = withContext(Dispatchers.Default) {
        val decoded = decodeMono(context, uri)
        val leadIn = leadInSamples(leadingSilence(decoded.samples), decoded.sampleRate)
        var pcm = trimSilence(decoded.samples)
        if (pcm.isEmpty()) throw BellConvertException(I18n.get(R.string.core_bell_silent))
        val maxSamples = ((MAX_SECONDS - LEAD_IN_MAX_SECONDS) * decoded.sampleRate).toInt()
        val trimmed = pcm.size > maxSamples
        if (trimmed) pcm = fadeOut(pcm.copyOf(maxSamples), decoded.sampleRate)
        edgeFade(pcm, decoded.sampleRate)
        if (!keepLevel) normalize(pcm)
        pcm = ShortArray(leadIn) + pcm
        val seconds = pcm.size / decoded.sampleRate.toFloat()
        val rate = bestSampleRate(seconds, decoded.sampleRate)
        val out = if (rate == decoded.sampleRate) pcm else resample(pcm, decoded.sampleRate, rate)
        val file = vmSound(wav(out, rate))
        if (file.size > MAX_FILE_BYTES) throw BellConvertException(I18n.get(R.string.core_bell_too_long))
        Result(file, rate, seconds, trimmed)
    }

    /** mooovy's rule: keep the source rate if it fits, else the first standard rate that fits. */
    fun bestSampleRate(seconds: Float, currentRate: Int): Int {
        if (currentRate * 2 * seconds < MAX_FILE_BYTES) return currentRate
        return SAMPLE_RATES.firstOrNull { it * 2 * seconds < MAX_FILE_BYTES } ?: SAMPLE_RATES.last()
    }

    fun wav(samples: ShortArray, rate: Int): ByteArray {
        val dataLen = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataLen); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    fun vmSound(wav: ByteArray): ByteArray {
        val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(wav.size).array()
        return VM_SOUND_HEADER + len + wav
    }

    private class Decoded(val samples: ShortArray, val sampleRate: Int)

    private fun decodeMono(context: Context, uri: Uri): Decoded {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw BellConvertException(I18n.get(R.string.core_bell_cannot_open))
        }
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw BellConvertException(I18n.get(R.string.core_bell_no_audio))
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw BellConvertException(I18n.get(R.string.core_bell_unsupported))
            }
            val out = ShortCollector()
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val maxSamplesHint = ((MAX_SECONDS + 30) * 48_000).toInt()
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outIndex >= 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            out.addInterleaved(buf.slice().order(ByteOrder.LITTLE_ENDIAN), channels, encoding)
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            // Long files: stop early, we only keep the first seconds anyway.
                            if (out.size > maxSamplesHint) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (out.size == 0) throw BellConvertException(I18n.get(R.string.core_bell_no_data))
            return Decoded(out.toArray(), sampleRate)
        } finally {
            extractor.release()
        }
    }

    private class ShortCollector {
        private var data = ShortArray(1 shl 16)
        var size = 0; private set

        fun addInterleaved(buf: ByteBuffer, channels: Int, encoding: Int) {
            val ch = max(1, channels)
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val fb = buf.asFloatBuffer()
                    while (fb.remaining() >= ch) {
                        var acc = 0f
                        repeat(ch) { acc += fb.get() }
                        add((acc / ch * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
                    }
                }
                AudioFormat.ENCODING_PCM_8BIT -> {
                    while (buf.remaining() >= ch) {
                        var acc = 0
                        repeat(ch) { acc += ((buf.get().toInt() and 0xFF) - 128) shl 8 }
                        add((acc / ch).toShort())
                    }
                }
                else -> {
                    val sb = buf.asShortBuffer()
                    while (sb.remaining() >= ch) {
                        var acc = 0
                        repeat(ch) { acc += sb.get() }
                        add((acc / ch).toShort())
                    }
                }
            }
        }

        private fun add(s: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = s
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    /** The silent lead-in a bell gets: the file's own, clamped to [LEAD_IN_MIN_SECONDS]..[LEAD_IN_MAX_SECONDS]. */
    fun leadInSamples(existing: Int, rate: Int): Int =
        existing.coerceIn((LEAD_IN_MIN_SECONDS * rate).toInt(), (LEAD_IN_MAX_SECONDS * rate).toInt())

    private fun leadingSilence(pcm: ShortArray, threshold: Int = 400): Int {
        var start = 0
        while (start < pcm.size && abs(pcm[start].toInt()) < threshold) start++
        return start
    }

    private fun trimSilence(pcm: ShortArray, threshold: Int = 400): ShortArray {
        var start = 0
        while (start < pcm.size && abs(pcm[start].toInt()) < threshold) start++
        var end = pcm.size
        while (end > start && abs(pcm[end - 1].toInt()) < threshold) end--
        return pcm.copyOfRange(start, end)
    }

    private fun fadeOut(pcm: ShortArray, rate: Int): ShortArray {
        val n = min(pcm.size, rate / 5)
        for (i in 0 until n) {
            val idx = pcm.size - n + i
            pcm[idx] = (pcm[idx] * (1f - i / n.toFloat())).toInt().toShort()
        }
        return pcm
    }

    /**
     * The silence trim cuts right at the threshold, so both ends can start and stop on a sample
     * of about -38 dBFS, which normalising lifts into an audible click. Ramp them in and out.
     */
    private fun edgeFade(pcm: ShortArray, rate: Int) {
        val fadeIn = min(pcm.size / 2, rate / 200)   // 5 ms
        val fadeOut = min(pcm.size / 2, rate / 100)  // 10 ms
        for (i in 0 until fadeIn) pcm[i] = (pcm[i] * (i / fadeIn.toFloat())).toInt().toShort()
        for (i in 0 until fadeOut) {
            val idx = pcm.size - 1 - i
            pcm[idx] = (pcm[idx] * (i / fadeOut.toFloat())).toInt().toShort()
        }
    }

    /** Peak-normalise to about -1 dBFS so the small bike speaker gets the full range. */
    private fun normalize(pcm: ShortArray) {
        val peak = pcm.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
        val gain = (29_200f / peak).coerceAtMost(8f)
        if (gain in 0.98f..1.02f) return
        for (i in pcm.indices) pcm[i] = (pcm[i] * gain).coerceIn(-32768f, 32767f).toInt().toShort()
    }

    /** Windowed-sinc low-pass, then linear interpolation. Good enough for a bell speaker. */
    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        val input = if (to < from) lowPass(pcm, 0.45 * to / from) else pcm
        val outLen = (input.size.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val i0 = floor(pos).toInt()
            val frac = pos - i0
            val a = input[min(i0, input.size - 1)]
            val b = input[min(i0 + 1, input.size - 1)]
            (a + (b - a) * frac).toInt().toShort()
        }
    }

    private fun lowPass(pcm: ShortArray, cutoff: Double, taps: Int = 63): ShortArray {
        val m = taps - 1
        val kernel = DoubleArray(taps) { n ->
            val x = n - m / 2.0
            val sinc = if (x == 0.0) 2 * PI * cutoff else sin(2 * PI * cutoff * x) / x
            sinc * (0.42 - 0.5 * cos(2 * PI * n / m) + 0.08 * cos(4 * PI * n / m))
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val half = m / 2
        return ShortArray(pcm.size) { i ->
            var acc = 0.0
            for (k in 0 until taps) {
                val j = i + k - half
                if (j in pcm.indices) acc += pcm[j] * kernel[k]
            }
            acc.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
