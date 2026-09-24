package com.detoxin.app.usage

import android.content.Context
import android.util.Log
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.entity.AppUsageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Перенос системной статистики в локальную базу (`app_usage_logs`) для графиков и истории.
 *
 * Курсор (момент, до которого всё уже записано) хранится локально. Каждая синхронизация читает
 * сессии в окне `[курсор, сейчас]`, обрезает их по окну, делит по полуночам и записывает.
 * Поэтому одна длинная сессия, прочитанная по частям, не считается дважды.
 *
 * Первый запуск заменяет сегодняшние записи старого трекера (он терял время при остановке службы)
 * точными данными системы; прошлые дни не трогаются.
 * Mutex защищает от одновременного запуска из службы и экрана аналитики (один процесс).
 */
object UsageSync {

    private const val TAG = "UsageSync"
    private const val PREFS = "detoxin_usage_sync"
    private const val KEY_CURSOR = "cursor_wall_ms"
    private const val MAX_BACKFILL_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val MIN_PIECE_MS = 500L

    private val mutex = Mutex()

    /** @return `false`, если нет доступа к статистике или запись не удалась. */
    suspend fun sync(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            if (!UsageStatsReader.hasAccess(appContext)) return@withLock false
            try {
                syncLocked(appContext)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Usage sync failed", e)
                false
            }
        }
    }

    private suspend fun syncLocked(context: Context) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val usageDao = DatabaseProvider.getDatabase(context).usageDao()

        var cursor = prefs.getLong(KEY_CURSOR, -1L)
        if (cursor < 0L) {
            val today = LocalDate.now(zone)
            usageDao.deleteFromDay(TrackedApp.packageNames.toList(), today.toEpochDay())
            cursor = today.atStartOfDay(zone).toInstant().toEpochMilli()
        }
        cursor = maxOf(cursor, now - MAX_BACKFILL_MS)
        if (now <= cursor) return

        val logs = UsageStatsReader.readSessions(context, cursor, now, TrackedApp.packageNames)
            .flatMap { session -> splitByDay(session, zone) }
            .filter { it.durationMs >= MIN_PIECE_MS }
        if (logs.isNotEmpty()) usageDao.insertAll(logs)
        prefs.edit().putLong(KEY_CURSOR, now).commit()
    }

    /** Часть сессии до 00:00 уходит во вчерашний день, остаток — в сегодняшний. */
    private fun splitByDay(session: AppSession, zone: ZoneId): List<AppUsageLog> {
        val pieces = ArrayList<AppUsageLog>(1)
        var start = session.startMs
        while (start < session.endMs) {
            val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(session.endMs, nextMidnight)
            pieces += AppUsageLog(
                packageName = session.packageName,
                sessionStartTime = start,
                durationMs = end - start,
                dateEpochDay = date.toEpochDay(),
            )
            start = end
        }
        return pieces
    }
}
