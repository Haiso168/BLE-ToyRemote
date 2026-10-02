package com.ycm.remote.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ycm.remote.alarm.AlarmReceiver
import java.util.Calendar

/**
 * 闹钟的持久化与调度。
 *
 * 设置存 SharedPreferences（进程被杀也能恢复），
 * 触发用 AlarmManager.setExactAndAllowWhileIdle（Doze 下也能按时响）。
 *
 * 注意：Android 12+ 精确闹钟需要 SCHEDULE_EXACT_ALARM 权限，
 * 用户可在系统设置里撤销，所以这里做降级处理。
 */
object AlarmScheduler {

    private const val PREFS = "ycm_alarm"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_SPEC = "spec"

    private const val REQUEST_CODE = 0x5C01

    data class Settings(
        val enabled: Boolean,
        val hour: Int,
        val minute: Int,
        val spec: RingtoneSpec,
    )

    // ------------------------------------------------------------------ 读写

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            hour = p.getInt(KEY_HOUR, 7),
            minute = p.getInt(KEY_MINUTE, 30),
            spec = RingtoneSpec.decode(p.getString(KEY_SPEC, null)),
        )
    }

    fun save(context: Context, settings: Settings) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_HOUR, settings.hour)
            .putInt(KEY_MINUTE, settings.minute)
            .putString(KEY_SPEC, settings.spec.encode())
            .apply()
    }

    // ------------------------------------------------------------------ 调度

    /**
     * 保存设置并重新调度。
     * 未启用时取消已有闹钟。
     */
    fun schedule(context: Context, settings: Settings): Boolean {
        save(context, settings)
        return if (settings.enabled) {
            setAlarm(context, settings.hour, settings.minute)
        } else {
            cancel(context)
            true
        }
    }

    /** @return 是否成功设定了精确闹钟（false 表示降级为非精确） */
    private fun setAlarm(context: Context, hour: Int, minute: Int): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)

        val triggerAt = nextTrigger(hour, minute)

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }

        return try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                true
            } else {
                // 没有精确闹钟权限：退化为非精确，可能晚几分钟
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                false
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            false
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    /** 下一个触发时间：今天的该时刻若已过则顺延到明天。 */
    fun nextTrigger(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
