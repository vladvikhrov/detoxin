package com.detoxin.app.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.ZoneId

/**
 * Смена дня для реактивного виджета без периодического опроса (`updatePeriodMillis = 0`).
 *
 * Один неточный non-wakeup будильник `RTC` на ближайшую локальную полночь: он не будит
 * устройство и доставляется при следующем пробуждении, поэтому не расходует батарею.
 * Перепланирование — при каждой отрисовке виджета (PendingIntent с тем же requestCode
 * заменяет предыдущий).
 */
internal object MidnightRefreshScheduler {

    const val ACTION_DAY_CHANGED = "com.detoxin.app.widget.ACTION_DAY_CHANGED"

    private const val REQUEST_CODE = 4_201
    private const val TRIGGER_DELAY_AFTER_MIDNIGHT_MS = 1_000L

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        alarmManager.set(
            AlarmManager.RTC,
            nextMidnight + TRIGGER_DELAY_AFTER_MIDNIGHT_MS,
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /** Снять будильник, только если на рабочем столе не осталось ни одного виджета Detoxin. */
    fun cancelIfNoWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val receivers = listOf(
            HabitGlanceWidgetReceiver::class.java,
            HabitWeekWidgetReceiver::class.java,
            ScreenTimeWidgetReceiver::class.java,
        )
        val anyLeft = receivers.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        if (!anyLeft) cancel(context)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HabitGlanceWidgetReceiver::class.java).setAction(ACTION_DAY_CHANGED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
