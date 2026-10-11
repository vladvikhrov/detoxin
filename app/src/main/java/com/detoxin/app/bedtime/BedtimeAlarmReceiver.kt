package com.detoxin.app.bedtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.util.ProcessScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Будильники «Отбоя» и системные события, после которых их нужно поставить заново
 * (перезагрузка, обновление приложения, смена времени или часового пояса).
 *
 * Работа идёт под `goAsync()` в [ProcessScope] на IO: чтение DataStore, системной статистики
 * и запись в Room не блокируют главный поток.
 */
class BedtimeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in handledActions) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        ProcessScope.io.launch {
            try {
                handle(appContext, action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Bedtime alarm '$action' failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        when (action) {
            BedtimeScheduler.ACTION_REMINDER -> {
                val settings = BedtimeRepository.current(context).effectiveAt(now)
                // Будильник мог сработать с опозданием (Doze): после начала отбоя напоминание бессмысленно.
                val untilStart = BedtimePolicy.nextWindow(now, zone, settings).startWall - now
                val lead = BedtimePolicy.REMINDER_LEAD_MINUTES * MS_PER_MINUTE
                if (settings.enabled && settings.hasWindow && untilStart in 1..lead + REMINDER_TOLERANCE_MS) {
                    BedtimeNotifications.showReminder(context, settings)
                }
            }
            BedtimeScheduler.ACTION_WAKE -> {
                BedtimeRepository.promotePending(context, now)
                BedtimeHabitEvaluator.evaluate(context, now, zone)
            }
        }
        BedtimeScheduler.rescheduleFromStore(context)
    }

    private companion object {
        const val TAG = "BedtimeAlarmReceiver"
        const val MS_PER_MINUTE = 60_000L
        const val REMINDER_TOLERANCE_MS = 5L * MS_PER_MINUTE

        val handledActions = setOf(
            BedtimeScheduler.ACTION_REMINDER,
            BedtimeScheduler.ACTION_WAKE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
