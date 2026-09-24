package com.detoxin.app.usage

import android.content.Context
import com.detoxin.app.core.TrackedApp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Снимок использования за текущие сутки и текущий календарный час. */
data class UsageSnapshot(
    val takenAtWall: Long,
    val takenAtElapsed: Long,
    val dayStartWall: Long,
    val hourStartWall: Long,
    val dayUsed: Map<String, Long>,
    val hourUsed: Map<String, Long>,
    /** Приложения, открытые в момент снимка: их время растёт до следующего снимка. */
    val openPackages: Set<String>,
)

/** Сработавший лимит: [untilWall] — когда он снимается (полночь или начало следующего часа). */
data class LimitBlock(
    val kind: Kind,
    val untilWall: Long,
) {
    enum class Kind { DAILY, HOURLY }
}

/**
 * Бюджет времени по системной статистике: сколько использовано сегодня и в этот час,
 * и какой лимит (если есть) сейчас действует. Дневной лимит важнее часового.
 */
object UsageBudget {

    fun dayStart(wallMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun hourStart(wallMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(wallMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    /** Binder-запрос к системе: вызывать на фоновом диспетчере. */
    fun snapshot(context: Context, nowWall: Long, nowElapsed: Long, zone: ZoneId): UsageSnapshot {
        val dayStart = dayStart(nowWall, zone)
        val hourStart = hourStart(nowWall, zone)
        val sessions = UsageStatsReader.readSessions(context, dayStart, nowWall, TrackedApp.packageNames)
        val dayUsed = HashMap<String, Long>()
        val hourUsed = HashMap<String, Long>()
        for (session in sessions) {
            dayUsed.merge(session.packageName, session.endMs - session.startMs, Long::plus)
            val overlap = session.endMs - maxOf(session.startMs, hourStart)
            if (overlap > 0L) hourUsed.merge(session.packageName, overlap, Long::plus)
        }
        return UsageSnapshot(
            takenAtWall = nowWall,
            takenAtElapsed = nowElapsed,
            dayStartWall = dayStart,
            hourStartWall = hourStart,
            dayUsed = dayUsed,
            hourUsed = hourUsed,
            openPackages = sessions.filter { it.isOpen }.map { it.packageName }.toSet(),
        )
    }

    fun decide(app: TrackedApp, dayUsedMs: Long, hourUsedMs: Long, nowWall: Long, zone: ZoneId): LimitBlock? {
        if (dayUsedMs >= app.dailyLimitMs) {
            val midnight = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            return LimitBlock(LimitBlock.Kind.DAILY, midnight)
        }
        val hourly = app.hourlyLimitMs ?: return null
        if (hourUsedMs >= hourly) {
            return LimitBlock(LimitBlock.Kind.HOURLY, hourStart(nowWall, zone) + ChronoUnit.HOURS.duration.toMillis())
        }
        return null
    }
}
