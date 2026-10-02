package com.ycm.remote.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ycm.remote.MainActivity
import com.ycm.remote.R
import com.ycm.remote.data.AlarmScheduler
import com.ycm.remote.data.PatternStore
import com.ycm.remote.data.RingtoneKind
import com.ycm.remote.ble.BleManager
import com.ycm.remote.ble.PatternPlayer
import com.ycm.remote.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 闹钟前台服务：真正干活的地方。
 *
 * 流程：
 *   1. 起前台通知（避免被系统杀）
 *   2. 尝试连接玩具（若当前没连上）
 *   3. 按铃声规格播放
 *   4. 播完自动暂停并结束
 *
 * 已知限制：Android 不允许后台随意建立 BLE 连接，
 * 如果 App 长时间没在前台，连接可能失败——此时会发一条通知提示用户手动打开 App。
 */
class AlarmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ble: BleManager? = null
    private var player: PatternPlayer? = null
    private var workJob: Job? = null
    private var connectedByUs = false

    companion object {
        private const val CHANNEL_ID = "ycm_alarm"
        private const val NOTIFICATION_ID = 0x5C02

        /** 连上玩具最多等这么久 */
        private const val CONNECT_TIMEOUT_MS = 12_000L

        /** 单次响铃最长时长，防止失控 */
        private const val MAX_RING_MS = 60_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmReceiver.ACTION_STOP -> {
                stopRinging()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("闹钟响铃中…", ringing = true))
                if (workJob?.isActive != true) {
                    workJob = scope.launch { runAlarm() }
                }
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ 主流程

    private suspend fun runAlarm() {
        val settings = AlarmScheduler.load(this)
        val spec = settings.spec

        val bleManager = BleManager(applicationContext)
        ble = bleManager
        player = PatternPlayer(bleManager)

        // 1) 确保连接
        if (bleManager.state.value != BleManager.State.READY) {
            val device = try {
                findPairedDevice()
            } catch (e: Exception) {
                null
            }
            if (device == null) {
                notifyProblem("没有找到已配对的 YCM-BL001，无法响铃")
                stopSelf()
                return
            }
            bleManager.connect(device)
            connectedByUs = true
            val ok = bleManager.awaitReady(CONNECT_TIMEOUT_MS)
            if (!ok) {
                notifyProblem("连接玩具失败，请打开 App 重连后再试")
                stopSelf()
                return
            }
        }

        // 2) 播放铃声
        val ringStart = System.currentTimeMillis()
        when (spec.kind) {
            RingtoneKind.MODE -> {
                bleManager.write(Protocol.frameMode(spec.modeCmd))
                delay(120)
                bleManager.write(Protocol.frameStrength(spec.strength))

                // 模式会一直持续，所以响一段时间后自动停
                while (System.currentTimeMillis() - ringStart < MAX_RING_MS) {
                    delay(1000)
                    if (workJob?.isActive != true) break
                }
            }

            RingtoneKind.PATTERN -> {
                val store = PatternStore(applicationContext)
                val pattern = spec.patternName?.let { store.load(it) }
                if (pattern == null) {
                    notifyProblem("找不到波形「${spec.patternName}」，请检查是否已保存")
                    stopSelf()
                    return
                }
                // 闹钟场景强制循环，直到超时或用户停止
                player?.play(pattern.copy(loop = true))
                while (System.currentTimeMillis() - ringStart < MAX_RING_MS) {
                    delay(1000)
                    if (workJob?.isActive != true) break
                }
            }
        }

        // 3) 收尾
        stopRinging()
        stopSelf()
    }

    private fun stopRinging() {
        workJob?.cancel()
        workJob = null
        player?.emergencyStop()
        player = null
        if (connectedByUs) {
            ble?.disconnect(quiet = true)
        }
        ble = null
        connectedByUs = false
    }

    private suspend fun findPairedDevice(): String? {
        // 复用 BleManager 的扫描能力，找一个已配对/在广播里的 YCM-BL001
        val manager = BleManager(applicationContext)
        manager.startScan()
        repeat(40) {   // 最多等 8 秒
            val found = manager.found.value.firstOrNull {
                it.name == BleManager.DEVICE_NAME
            }
            if (found != null) {
                manager.stopScan()
                return found.address
            }
            delay(200)
        }
        manager.stopScan()
        return null
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "闹钟",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "玩具闹钟响铃提示"
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, ringing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingFlags(),
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("YCM 玩具闹钟")
            .setContentText(text)
            .setOngoing(ringing)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)

        if (ringing) {
            val stopIntent = PendingIntent.getBroadcast(
                this,
                1,
                Intent(this, AlarmReceiver::class.java).apply {
                    action = AlarmReceiver.ACTION_STOP
                },
                pendingFlags(),
            )
            builder.addAction(0, "停止", stopIntent)
        }
        return builder.build()
    }

    private fun notifyProblem(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("玩具闹钟未能响铃")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    override fun onDestroy() {
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }
}
