package com.ycm.remote.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ycm.remote.protocol.Pattern
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 手机端离线分析：把音频文件解码成 PCM，再检测节拍，生成可播放的波形。
 *
 * 这是「律动」第三条路线在**不依赖电脑**情况下的实现：
 *   导入音频 → 解码 → 检测 onset → 生成 [Pattern] → 存进波形库 → 直接播放
 *
 * 优点：零延迟、节拍精准（因为是回放预生成的时序，不是实时听音）。
 * 缺点：只对导入过的音频有效。
 */
object AudioAnalyzer {

    /** 分析用的目标采样率。够定位鼓点，又省内存。 */
    private const val TARGET_SR = 22050

    /** 分析窗口（毫秒），用于算起音包络 */
    private const val HOP_MS = 10

    /** 两个节拍的最小间隔（毫秒），防止一次鼓点被拆成多个 */
    private const val MIN_GAP_MS = 90

    data class Result(
        val pattern: Pattern,
        val bpm: Double,
        val beatCount: Int,
        val durationMs: Long,
    )

    data class Options(
        var minStrength: Int = 30,
        var maxStrength: Int = 95,
        var decayMs: Int = 120,
        var releaseMs: Int = 60,
        var maxHoldMs: Int = 400,
    )

    // ------------------------------------------------------------------ 主流程

    /**
     * 分析音频文件。
     * 耗时操作，**必须在后台线程调用**（解码一首歌通常几秒）。
     */
    fun analyze(
        context: Context,
        filePath: String,
        name: String,
        options: Options = Options(),
    ): Result {
        val (pcm, sr) = decodeToMonoPcm(filePath)
        require(pcm.isNotEmpty()) { "解码后没有得到音频数据" }

        val resampled = if (sr != TARGET_SR) resample(pcm, sr, TARGET_SR) else pcm
        val env = onsetEnvelope(resampled, TARGET_SR)
        val bpm = estimateBpm(env, HOP_MS / 1000.0)
        val peaks = pickPeaks(env, HOP_MS / 1000.0)
        require(peaks.isNotEmpty()) { "没有检测到节拍，换一首节奏明显的音频试试" }

        val steps = peaksToSteps(peaks, options)
        val durationMs = (resampled.size.toLong() * 1000L) / TARGET_SR

        return Result(
            pattern = Pattern(name = name, steps = steps, loop = false, repeat = 1),
            bpm = bpm,
            beatCount = peaks.size,
            durationMs = durationMs,
        )
    }

    // ------------------------------------------------------------------ 解码

    /**
     * 用 MediaExtractor + MediaCodec 把音频解成单声道 FloatArray。
     * 支持手机系统能解的格式（mp3 / m4a / aac / flac / ogg / wav）。
     */
    private fun decodeToMonoPcm(path: String): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "文件里没有音频轨道" }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Float>(sampleRate * 60)
        val info = MediaCodec.BufferInfo()

        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex >= 0 -> {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        appendPcm(buf, channelCount, out)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return out.toFloatArray() to sampleRate
    }

    /** 把解码出的 buffer 转成 float，并按声道求平均得到单声道。 */
    private fun appendPcm(buffer: ByteBuffer, channelCount: Int, out: ArrayList<Float>) {
        buffer.order(ByteOrder.nativeOrder())
        val shorts = buffer.asShortBuffer()
        val frames = shorts.remaining() / channelCount
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channelCount) {
                sum += shorts.get() / 32768f
            }
            out.add(sum / channelCount)
        }
    }

    /** 线性插值重采样。 */
    private fun resample(input: FloatArray, fromSr: Int, toSr: Int): FloatArray {
        val n = (input.size.toLong() * toSr / fromSr).toInt()
        val out = FloatArray(n)
        val ratio = fromSr.toDouble() / toSr
        for (i in 0 until n) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    // ------------------------------------------------------------------ 分析

    /**
     * 起音包络（onset envelope）。
     *
     * 思路：把信号分成「低频(≤200Hz)」和「全频」两路，
     * 各算短时能量的一阶正向差分（能量上升沿 = 打击点），低频加权更高——
     * 因为鼓点能量主要在低频，这样能突出节拍而不是人声。
     */
    private fun onsetEnvelope(x: FloatArray, sr: Int): FloatArray {
        val hop = (sr * HOP_MS / 1000).coerceAtLeast(1)
        val win = hop * 2

        // 一阶低通（简单的单极点 IIR），不用 scipy 那套
        val low = FloatArray(x.size)
        val alpha = 0.15f
        var prev = 0f
        for (i in x.indices) {
            prev += alpha * (x[i] - prev)
            low[i] = prev
        }

        fun frameEnergy(sig: FloatArray): FloatArray {
            val n = ((sig.size - win) / hop + 1).coerceAtLeast(1)
            val e = FloatArray(n)
            for (i in 0 until n) {
                var s = 0f
                val base = i * hop
                for (j in 0 until win) {
                    val v = sig[base + j]
                    s += v * v
                }
                e[i] = sqrt(s / win)
            }
            return e
        }

        val eLow = frameEnergy(low)
        val eFull = frameEnergy(x)
        val n = minOf(eLow.size, eFull.size)

        val env = FloatArray(n)
        for (i in 0 until n) {
            val dLow = if (i == 0) 0f else (eLow[i] - eLow[i - 1]).coerceAtLeast(0f)
            val dFull = if (i == 0) 0f else (eFull[i] - eFull[i - 1]).coerceAtLeast(0f)
            env[i] = 0.75f * dLow + 0.25f * dFull
        }

        // 3 点平滑
        val smoothed = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            var c = 0
            for (k in -1..1) {
                val j = i + k
                if (j in 0 until n) { s += env[j]; c++ }
            }
            smoothed[i] = s / c
        }
        return smoothed
    }

    /** 自相关估 BPM，归一到 70-180。 */
    private fun estimateBpm(env: FloatArray, dt: Double): Double {
        if (env.size < 32) return 120.0

        val mean = env.average().toFloat()
        val e = FloatArray(env.size) { env[it] - mean }

        val minLag = ((60.0 / 200.0) / dt).toInt().coerceAtLeast(1)
        val maxLag = ((60.0 / 60.0) / dt).toInt().coerceAtMost(e.size - 1)
        if (maxLag <= minLag) return 120.0

        var bestLag = minLag
        var bestVal = -Float.MAX_VALUE
        for (lag in minLag..maxLag) {
            var s = 0f
            for (i in 0 until e.size - lag) s += e[i] * e[i + lag]
            if (s > bestVal) { bestVal = s; bestLag = lag }
        }

        var bpm = 60.0 / (bestLag * dt)
        while (bpm < 70) bpm *= 2
        while (bpm > 180) bpm /= 2
        return bpm
    }

    /** 自适应阈值挑峰，返回 (时间秒, 强度0..1) */
    private fun pickPeaks(env: FloatArray, dt: Double): List<Pair<Double, Double>> {
        if (env.isEmpty()) return emptyList()

        // 滑动平均作为局部基线（窗口约 0.8 秒）
        val w = (0.8 / dt).toInt().coerceAtLeast(3)
        val baseline = FloatArray(env.size)
        for (i in env.indices) {
            var s = 0f
            var c = 0
            for (k in -w / 2..w / 2) {
                val j = i + k
                if (j in env.indices) { s += env[j]; c++ }
            }
            baseline[i] = s / c
        }

        val sorted = env.sortedArray()
        val noise = sorted[(sorted.size * 0.6).toInt().coerceIn(0, sorted.size - 1)]
        val ref = sorted[(sorted.size * 0.99).toInt().coerceIn(0, sorted.size - 1)] + 1e-9f

        val minGap = (MIN_GAP_MS / 1000.0 / dt).toInt().coerceAtLeast(1)
        val peaks = ArrayList<Pair<Double, Double>>()
        var lastPeak = -minGap

        for (i in 1 until env.size - 1) {
            val v = env[i]
            if (v <= env[i - 1] || v < env[i + 1]) continue
            if (v < baseline[i] * 1.35f) continue
            if (v < noise * 1.6f) continue
            if (i - lastPeak < minGap) continue

            peaks.add(i * dt to (v / ref).coerceIn(0f, 1f).toDouble())
            lastPeak = i
        }
        return peaks
    }

    /** 把节拍转成 (力度, 持续毫秒) 步骤序列，用 0 步填补间隔以保持时间轴。 */
    private fun peaksToSteps(
        peaks: List<Pair<Double, Double>>,
        o: Options,
    ): List<Pattern.Step> {
        val steps = ArrayList<Pattern.Step>()
        if (peaks.isEmpty()) return steps

        var cursorMs = (peaks.first().first * 1000).toLong()

        for ((t, s) in peaks) {
            val tMs = (t * 1000).toLong()

            val gap = tMs - cursorMs
            if (gap > 0) {
                steps.add(Pattern.Step(0, gap.coerceAtMost(o.maxHoldMs.toLong())))
            }

            val strength = (o.minStrength + (o.maxStrength - o.minStrength) * s)
                .roundToInt()
                .coerceIn(o.minStrength, o.maxStrength)
            steps.add(Pattern.Step(strength, o.decayMs.toLong()))
            steps.add(Pattern.Step(0, o.releaseMs.toLong()))
            cursorMs = tMs + o.decayMs + o.releaseMs
        }
        return steps
    }
}
