package com.ycm.remote.audio

/**
 * 音频源抽象。
 *
 * 律动功能有三条技术路线，各自音源不同，但对上层（节拍分析）暴露的接口一致：
 *
 *   [MicSource]              —— 麦克风实时采集，任何音源都能用，但受环境噪声影响
 *   [PlaybackCaptureSource]  —— 系统内录，信号最干净，但要求音源 App 允许被录制
 *   [FileSource]             —— 离线预分析，零延迟，但只对分析过的歌有效
 */
interface AudioSource {

    /** 音频块回调。samples 是 [-1,1] 的归一化 PCM，sampleRate 是采样率。 */
    fun interface Listener {
        fun onAudio(samples: FloatArray, sampleRate: Int)
    }

    val name: String

    /** 该音源在当前设备/系统上是否可用（不含运行时授权） */
    fun isAvailable(): Boolean

    /**
     * 开始采集。必须在已获得必要权限后调用。
     * @return 成功启动返回 true
     */
    fun start(listener: Listener): Boolean

    /** 停止采集并释放资源。可重复调用。 */
    fun stop()

    /** 采集过程中发生的错误信息，供 UI 展示。 */
    val lastError: String?
}

/** 采集参数：统一用单声道 + 16kHz，够做节拍检测，又省电。 */
object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val CHANNEL_MASK = android.media.AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = android.media.AudioFormat.ENCODING_PCM_16BIT

    /** 每次回调的帧数。256 帧 @16kHz = 16ms，兼顾实时性与开销。 */
    const val FRAMES_PER_BLOCK = 256
}
