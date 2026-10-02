package com.ycm.remote.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.concurrent.thread

/**
 * 麦克风实时采集。
 *
 * 优点：任何音源都能用（音箱、蓝牙音箱、现场），最通用。
 * 缺点：受环境噪声与房间混响影响；若玩具离音箱太近可能采到玩具自身的声音。
 *
 * 延迟：AudioRecord 的 buffer 通常 20-80ms，加上分析窗口，体感落后 100-200ms。
 */
class MicSource : AudioSource {

    override val name: String = "麦克风"

    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    override var lastError: String? = null
        private set

    override fun isAvailable(): Boolean = true

    @SuppressLint("MissingPermission")
    override fun start(listener: AudioSource.Listener): Boolean {
        if (running) return true
        lastError = null

        val minBuf = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_MASK,
            AudioConfig.ENCODING,
        )
        if (minBuf <= 0) {
            lastError = "设备不支持该录音参数（getMinBufferSize=$minBuf）"
            return false
        }

        // 取最小缓冲的 2 倍，降低溢出概率
        val bufSizeBytes = minBuf * 2

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_MASK,
                AudioConfig.ENCODING,
                bufSizeBytes,
            )
        } catch (e: SecurityException) {
            lastError = "没有录音权限：${e.message}"
            return false
        } catch (e: Exception) {
            lastError = "创建 AudioRecord 失败：${e.message}"
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            lastError = "AudioRecord 初始化失败（可能是麦克风被其他 App 占用）"
            return false
        }

        record = recorder
        running = true

        worker = thread(name = "ycm-mic", priority = Process.THREAD_PRIORITY_AUDIO) {
            val shortBuf = ShortArray(AudioConfig.FRAMES_PER_BLOCK)
            val floatBuf = FloatArray(AudioConfig.FRAMES_PER_BLOCK)
            recorder.startRecording()

            while (running) {
                val read = recorder.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        lastError = "录音读取错误（code=$read）"
                        break
                    }
                    continue
                }
                for (i in 0 until read) {
                    floatBuf[i] = shortBuf[i] / 32768f
                }
                listener.onAudio(
                    if (read == floatBuf.size) floatBuf else floatBuf.copyOf(read),
                    AudioConfig.SAMPLE_RATE,
                )
            }

            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        return true
    }

    override fun stop() {
        running = false
        worker?.join(500)
        worker = null
        record = null
    }
}

/**
 * AudioPlaybackCapture（Android 10+）：直接捕获系统混音后的数字音频。
 *
 * 优点：信号最干净，没有环境噪声与房间频响失真。
 * 缺点（三个硬条件，缺一不可）：
 *   1. 被录制的 App 必须允许被捕获（allowAudioPlaybackCapture / ALLOW_CAPTURE_BY_ALL）。
 *      **主流商业音乐 App 基本都禁止**，这是最容易失败的一点。
 *   2. 音频必须走本机输出——**捕获不到发往蓝牙设备的音频**。
 *   3. 需要 RECORD_AUDIO 权限 + 一次 MediaProjection 授权。
 *
 * 如果音源被禁止，通常表现为"能启动但一直读到静音"，所以上层要做静音检测。
 */
class PlaybackCaptureSource(
    private val context: android.content.Context,
    private val projection: android.media.projection.MediaProjection,
) : AudioSource {

    override val name: String = "系统内录"

    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    override var lastError: String? = null
        private set

    override fun isAvailable(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    @SuppressLint("MissingPermission")
    override fun start(listener: AudioSource.Listener): Boolean {
        if (!isAvailable()) {
            lastError = "系统内录需要 Android 10 及以上"
            return false
        }
        if (running) return true
        lastError = null

        val minBuf = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_MASK,
            AudioConfig.ENCODING,
        )
        if (minBuf <= 0) {
            lastError = "设备不支持该录音参数"
            return false
        }

        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioConfig.ENCODING)
            .setSampleRate(AudioConfig.SAMPLE_RATE)
            .setChannelMask(AudioConfig.CHANNEL_MASK)
            .build()

        val recorder = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            lastError = "创建内录 AudioRecord 失败：${e.message}"
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            lastError = "内录初始化失败（音源 App 可能禁止了被录制）"
            return false
        }

        record = recorder
        running = true

        worker = thread(name = "ycm-capture", priority = Process.THREAD_PRIORITY_AUDIO) {
            val shortBuf = ShortArray(AudioConfig.FRAMES_PER_BLOCK)
            val floatBuf = FloatArray(AudioConfig.FRAMES_PER_BLOCK)
            recorder.startRecording()

            while (running) {
                val read = recorder.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    floatBuf[i] = shortBuf[i] / 32768f
                }
                listener.onAudio(
                    if (read == floatBuf.size) floatBuf else floatBuf.copyOf(read),
                    AudioConfig.SAMPLE_RATE,
                )
            }

            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        return true
    }

    override fun stop() {
        running = false
        worker?.join(500)
        worker = null
        record = null
    }
}
