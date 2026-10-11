package com.detoxin.app.bedtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.detoxin.app.usage.BedtimeConfig
import com.detoxin.app.usage.BedtimePolicy
import java.time.ZoneId

/**
 * Будильники «Отбоя»: напоминание за 30 минут до начала окна и утренний подъём (автопривычка
 * «Лёг вовремя», вступление в силу отложенного ослабления, перепланирование).
 *
 * `setAndAllowWhileIdle` (RTC_WAKEUP) срабатывает и в Doze, но не требует разрешения на точные
 * будильники `SCHEDULE_EXACT_ALARM`: система может сдвинуть его на несколько минут, что для
 * напоминания и утренней проверки несущественно. Каждый будильник одноразовый и после
 * срабатывания ставит следующий; PendingIntent с тем же requestCode заменяет предыдущий.
 * После перезагрузки, смены времени и обновления приложения будильники ставятся заново
 * ([BedtimeAlarmReceiver]).
 */
object BedtimeScheduler {

    const val ACTION_REMINDER = "com.detoxin.app.bedtime.ACTION_REMINDER"
    const val ACTION_WAKE = "com.detoxin.app.bedtime.ACTION_WAKE"

    /** Напоминание снимается само к началу отбоя. */
    const val REMINDER_TIMEOUT_MS = BedtimePolicy.REMINDER_LEAD_MINUTES * 60_000L

    private const val REQUEST_REMINDER = 7_201
    private const val REQUEST_WAKE = 7_202

    /** Проверка ночи чуть позже подъёма: последняя сессия успевает попасть в статистику. */
    private const val WAKE_DELAY_MS = 60_000L

    fun reschedule(context: Context, config: BedtimeConfig, nowWall: Long, zone: ZoneId) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val settings = config.effectiveAt(nowWall)
        if (!settings.enabled || !settings.hasWindow) {
            alarmManager.cancel(pendingIntent(context, ACTION_REMINDER, REQUEST_REMINDER))
            alarmManager.cancel(pendingIntent(context, ACTION_WAKE, REQUEST_WAKE))
            return
        }
        val reminderAt = BedtimePolicy.nextReminderAt(nowWall, zone, settings)
        var wakeAt = BedtimePolicy.nextWakeAt(nowWall, zone, settings)
        config.pendingAt(nowWall)?.let { wakeAt = minOf(wakeAt, config.pendingFromWall) }

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminderAt,
            pendingIntent(context, ACTION_REMINDER, REQUEST_REMINDER),
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            wakeAt + WAKE_DELAY_MS,
            pendingIntent(context, ACTION_WAKE, REQUEST_WAKE),
        )
    }

    /** Перепланирование по сохранённой конфигурации; выполняет чтение DataStore — вызывать вне Main. */
    suspend fun rescheduleFromStore(context: Context) {
        val appContext = context.applicationContext
        reschedule(appContext, BedtimeRepository.current(appContext), System.currentTimeMillis(), ZoneId.systemDefault())
    }

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, BedtimeAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
