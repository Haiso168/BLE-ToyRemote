package com.ycm.remote.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 音乐律动引擎：把音频块转成 0-100 的力度值流。
 *
 * 两种映射模式：
 *
 *   [Mode.DIRECT] 直接跟随响度。反应快、连贯，但体感偏"糊"——
 *                 因为音乐里持续的音量会让力度一直维持在高位。
 *
 *   [Mode.BEAT]   节拍增强。维护一个自适应阈值，只在"明显超过近期平均"时
 *                 触发一次冲击，然后按衰减曲线回落。听感更像"跟着鼓点打"。
 *                 **推荐用这个**，多数情况效果更好。
 *
 * 无论哪种模式，输出的力度值都会：
 *   1. 减去噪声底（floor），避免静音时的底噪驱动玩具
 *   2. 乘增益（gain）后夹到 0-100
 *   3. 低于最小阈值时输出 0（当作停止）
 */
class RhythmEngine(private val context: Context) {

    enum class Mode(val label: String) {
        DIRECT("直接跟随"),
        BEAT("节拍增强"),
    }

    data class Config(
        /** 增益：越大越灵敏。1.0 表示不放大 */
        var gain: Float = 6.0f,
        /** 噪声底：RMS 低于此值当作静音，用于过滤底噪 */
        var floor: Float = 0.012f,
        /** 输出低于此力度就不发指令（当作停止） */
        var minStrength: Int = 12,
        /** 力度上限，防止过于刺激 */
        var maxStrength: Int = 100,
        /** 模式 */
        var mode: Mode = Mode.BEAT,
        /** BEAT 模式的触发灵敏度：当前值需超过(近期均值 × 该系数)才判定为节拍 */
        var beatSensitivity: Float = 1.6f,
        /** BEAT 模式的衰减速度（每秒掉多少力度值） */
        var decayPerSecond: Float = 220f,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _level = MutableStateFlow(0f)          // 当前音频响度 RMS，供 UI 显示
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _strength = MutableStateFlow(0)        // 当前算出的力度
    val strength: StateFlow<Int> = _strength.asStateFlow()

    private val _beatCount = MutableStateFlow(0)
    val beatCount: StateFlow<Int> = _beatCount.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 力度变化回调。返回的 Int 是 0-100 的力度（0 表示停止）。 */
    var onStrength: ((Int) -> Unit)? = null

    private var source: AudioSource? = null
    private var emitJob: Job? = null

    /** 平滑后的响度，避免逐块抖动 */
    private var smoothed = 0f

    /** 近期均值，用于自适应节拍阈值 */
    private var runningMean = 0f

    /** BEAT 模式的当前力度（按衰减曲线回落） */
    private var beatStrength = 0f

    private var lastBeatAt = 0L

    // ------------------------------------------------------------------ 控制

    fun updateConfig(block: (Config) -> Unit) {
        val next = _config.value.copy()
        block(next)
        _config.value = next
    }

    fun start(src: AudioSource) {
        stop()
        source = src

        val started = src.start { samples, _ -> onAudio(samples) }
        if (!started) {
            _running.value = false
            return
        }
        _running.value = true

        // 以固定频率把当前力度推给 BLE，避免音频块的抖动直接传到写入
        emitJob = scope.launch {
            var lastEmitted = -1
            while (isActive) {
                val cfg = _config.value
                val raw = when (cfg.mode) {
                    Mode.DIRECT -> directStrength()
                    Mode.BEAT -> beatStrength
                }
                val value = raw.toInt().coerceIn(0, cfg.maxStrength)
                val finalValue = if (value < cfg.minStrength) 0 else value

                if (finalValue != lastEmitted) {
                    lastEmitted = finalValue
                    _strength.value = finalValue
                    onStrength?.invoke(finalValue)
                }
                delay(50)
            }
        }
    }

    fun stop() {
        emitJob?.cancel()
        emitJob = null
        source?.stop()
        source = null
        _running.value = false
        _strength.value = 0
        _level.value = 0f
        smoothed = 0f
        runningMean = 0f
        beatStrength = 0f
        onStrength?.invoke(0)
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    // ------------------------------------------------------------------ 分析

    private fun onAudio(samples: FloatArray) {
        if (samples.isEmpty()) return

        // 1) 计算 RMS
        var sum = 0f
        for (s in samples) sum += s * s
        val rms = sqrt(sum / samples.size)

        // 2) 平滑（一阶低通）
        smoothed = smoothed * 0.7f + rms * 0.3f
        runningMean = runningMean * 0.995f + rms * 0.005f
        _level.value = smoothed

        val cfg = _config.value
        val effective = smoothed - cfg.floor
        if (effective <= 0f) {
            // 静音段：让力度自然衰减下去
            beatStrength = (beatStrength - cfg.decayPerSecond * 0.05f).coerceAtLeast(0f)
            return
        }

        when (cfg.mode) {
            Mode.DIRECT -> {
                // 由 directStrength() 在 emit 循环里实时计算
            }

            Mode.BEAT -> {
                // 自适应阈值：明显超过近期均值才算一次节拍
                val threshold = (runningMean * cfg.beatSensitivity).coerceAtLeast(cfg.floor)
                val now = System.currentTimeMillis()
                if (smoothed > threshold && now - lastBeatAt > 60) {
                    lastBeatAt = now
                    // 超出阈值越多，冲击越强
                    val excess = (smoothed - threshold) / threshold.coerceAtLeast(0.0001f)
                    val hit = ((0.45f + excess * 0.55f) * cfg.maxStrength).coerceIn(0f, cfg.maxStrength.toFloat())
                    if (hit > beatStrength) beatStrength = hit
                    _beatCount.value = _beatCount.value + 1
                } else {
                    // 衰减
                    beatStrength = (beatStrength - cfg.decayPerSecond * 0.05f).coerceAtLeast(0f)
                }
            }
        }
    }

    private fun directStrength(): Float {
        val cfg = _config.value
        val effective = (smoothed - cfg.floor).coerceAtLeast(0f)
        val scaled = effective * cfg.gain * 100f
        return scaled.coerceIn(0f, cfg.maxStrength.toFloat())
    }

    /** 清空节拍计数 */
    fun resetBeatCount() { _beatCount.value = 0 }
}
