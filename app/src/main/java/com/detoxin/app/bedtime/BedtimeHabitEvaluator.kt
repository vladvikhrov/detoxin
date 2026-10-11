package com.detoxin.app.bedtime

import android.content.Context
import android.util.Log
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.entity.HabitAutoSource
import com.detoxin.app.ui.widget.HabitGlanceWidget
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.NightWindow
import com.detoxin.app.usage.UsageStatsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Автопривычка «Лёг вовремя» ([HabitAutoSource.BEDTIME]).
 *
 * Ночь засчитывается, если за окно сна TikTok и YouTube суммарно использовались не дольше
 * ночного лимита (плюс [BedtimePolicy.AUTO_HABIT_GRACE_MS] на секунды под экраном блокировки)
 * и защита ни разу не выключалась. Отметка ставится на день подъёма — утром привычка уже
 * отмечена «за сегодня», и виджет это показывает.
 *
 * Проверяются последние [NIGHTS_TO_CHECK] завершённых ночей (система хранит события
 * использования примерно неделю), поэтому пропущенный будильник догоняется при следующем
 * запуске. Отметка добавляется только в день без записи: ручное решение пользователя
 * (в том числе снятая отметка) не перезаписывается. Без доступа к статистике ночь не
 * оценивается — подтвердить её нечем.
 */
object BedtimeHabitEvaluator {

    private const val TAG = "BedtimeHabitEvaluator"
    private const val NIGHTS_TO_CHECK = 7

    private val mutex = Mutex()

    suspend fun evaluate(context: Context, nowWall: Long, zone: ZoneId) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            val settings = BedtimeRepository.current(appContext).effectiveAt(nowWall)
            if (!settings.enabled || !settings.hasWindow) return@withLock
            if (!UsageStatsReader.hasAccess(appContext)) return@withLock

            val database = DatabaseProvider.getDatabase(appContext)
            val habitDao = database.habitDao()
            val habits = habitDao.getHabitsWithAutoSource(HabitAutoSource.BEDTIME.key)
            if (habits.isEmpty()) return@withLock

            val latest = BedtimePolicy.latestStartedWindow(nowWall, zone, settings)
            val nights = (0 until NIGHTS_TO_CHECK)
                .map { BedtimePolicy.windowStartingOn(latest.startDate.minusDays(it.toLong()), zone, settings) }
                .filter { it.endWall <= nowWall }

            var marked = 0
            for (night in nights) {
                if (!isCleanNight(appContext, night, settings.allowanceMs)) continue
                val wakeDay = BedtimePolicy.dateOf(night.endWall, zone).toEpochDay()
                for (habit in habits) {
                    val createdDay = Instant.ofEpochMilli(habit.createdAt).atZone(zone).toLocalDate().toEpochDay()
                    if (wakeDay < createdDay) continue
                    if (habitDao.markCompletedIfAbsent(habit.id, wakeDay)) marked++
                }
            }
            if (marked > 0) {
                Log.i(TAG, "Auto-marked $marked bedtime check-ins")
                HabitGlanceWidget.refreshAll(appContext)
            }
        }
    }

    private suspend fun isCleanNight(context: Context, night: NightWindow, allowanceMs: Long): Boolean {
        val gaps = DatabaseProvider.getDatabase(context).protectionGapDao()
            .getOverlapping(night.startWall, night.endWall)
        if (gaps.isNotEmpty()) return false
        val usedMs = UsageStatsReader.readSessions(context, night.startWall, night.endWall, TrackedApp.packageNames)
            .sumOf { it.endMs - it.startMs }
        return usedMs <= allowanceMs + BedtimePolicy.AUTO_HABIT_GRACE_MS
    }
}
