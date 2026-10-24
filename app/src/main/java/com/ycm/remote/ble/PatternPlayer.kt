package com.ycm.remote.ble

import com.ycm.remote.device.DeviceProfile
import com.ycm.remote.device.YcmBl001Profile
import com.ycm.remote.protocol.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 时序引擎：把 [Pattern] 按时间轴推成一串 BLE 写入。
 *
 * 这是本 App 相对官方小程序的核心增量——小程序只能发单点力度，
 * 我们能编排任意序列。
 *
 * 力度值语义由当前连接的设备档案决定：
 *   - YCM-BL001：0-100
 *   - LEGACY（DSJM / QUXIN / ZAOQUYUN）：0-10
 *
 * 所以这里不写死任何帧格式，一律通过 [profileProvider] 取当前档案来构造指令。
 */
class PatternPlayer(
    private val ble: BleManager,
    private val profileProvider: () -> DeviceProfile = { ble.activeProfile.value ?: YcmBl001Profile },
) {

    data class Progress(
        val playing: Boolean = false,
        val patternName: String = "",
        val stepIndex: Int = 0,
        val stepCount: Int = 0,
        val strength: Int = 0,
        val loopRound: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    val isPlaying: Boolean get() = job?.isActive == true

    /**
     * 播放波形。会自动停掉上一条。
     *
     * @param repeat 整套重复次数（pattern.loop 为 true 时无限循环）
     */
    fun play(pattern: Pattern, repeat: Int = pattern.repeat) {
        stop(quiet = true)
        job = scope.launch {
            val infinite = pattern.loop
            val rounds = if (infinite) Int.MAX_VALUE else repeat.coerceAtLeast(1)

            var round = 0
            while (isActive && round < rounds) {
                for ((index, step) in pattern.steps.withIndex()) {
                    if (!isActive) break

                    _progress.value = Progress(
                        playing = true,
                        patternName = pattern.name,
                        stepIndex = index + 1,
                        stepCount = pattern.steps.size,
                        strength = step.strength,
                        loopRound = round + 1,
                    )

                    // 每步都重新取档案：播放中途换设备也能正确构造指令
                    val profile = profileProvider()
                    val frame = if (step.strength <= profile.strengthMin) {
                        profile.stopCommand()
                    } else {
                        profile.strengthCommand(step.strength)
                    }
                    ble.write(frame)

                    // 用可中断的 delay，stop() 时能立刻退出
                    delay(step.durationMs)
                }
                round++
            }

            // 播完自动停，安全兜底
            ble.write(profileProvider().stopCommand())
            _progress.value = Progress()
        }
    }

    /** 停止播放并发送暂停。 */
    fun stop(quiet: Boolean = false) {
        job?.cancel()
        job = null
        _progress.value = Progress()
        if (!quiet) {
            scope.launch { ble.writeWithRetry(profileProvider().stopCommand()) }
        }
    }

    /**
     * 急停：无论当前状态都发停止帧（用重试确保送达）。
     * 供 UI 上的常驻急停按钮调用。
     */
    fun emergencyStop() {
        job?.cancel()
        job = null
        _progress.value = Progress()
        scope.launch { ble.writeWithRetry(profileProvider().stopCommand(), attempts = 5) }
    }

    /** 平滑爬升到目标力度，用于替代生硬的跳变。 */
    fun rampTo(
        from: Int,
        to: Int,
        durationMs: Long,
        stepMs: Long = 60L,
    ) {
        if (durationMs <= 0 || stepMs <= 0) return
        val profile = profileProvider()
        val steps = (durationMs / stepMs).toInt().coerceAtLeast(1)
        val list = ArrayList<Pattern.Step>(steps)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val v = (from + (to - from) * t).toInt()
                .coerceIn(profile.strengthMin, profile.strengthMax)
            list.add(Pattern.Step(v, stepMs))
        }
        play(Pattern("爬升", list, loop = false, repeat = 1), repeat = 1)
    }
}
