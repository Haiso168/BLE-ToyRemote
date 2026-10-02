package com.ycm.remote.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.ycm.remote.data.AlarmScheduler

/**
 * 闹钟触发入口。
 *
 * 只做一件事：把工作转交给前台服务（[AlarmService]）——
 * 因为 BLE 连接和波形播放不能在 BroadcastReceiver 里做（10 秒就超时）。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM = "com.ycm.remote.action.ALARM"
        const val ACTION_STOP = "com.ycm.remote.action.ALARM_STOP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> {
                val serviceIntent = Intent(context, AlarmService::class.java).apply {
                    action = ACTION_ALARM
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // 闹钟是"每天"的，触发后重新排下一天
                val settings = AlarmScheduler.load(context)
                if (settings.enabled) {
                    AlarmScheduler.schedule(context, settings)
                }
            }

            ACTION_STOP -> {
                val serviceIntent = Intent(context, AlarmService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(serviceIntent)
            }
        }
    }
}
