package com.detoxin.app.usage

import android.content.Context
import com.detoxin.app.core.TrackedApp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Снимок использования за текущие «сутки бюджета», текущий календарный час и текущую ночь.
 *
 * @property dayStartWall начало суток бюджета ([BedtimePolicy.budgetDayStart]): полночь или,
 * если идёт окно сна, начало вчерашнего дня.
 * @property nightStartWall начало окна сна, если снимок сделан ночью; иначе `null`.
 * @property nightUsedMs суммарное время всех [TrackedApp] с начала окна сна.
 */
data class UsageSnapshot(
    val takenAtWall: Long,
    val takenAtElapsed: Long,
    val dayStartWall: Long,
    val hourStartWall: Long,
    val nightStartWall: Long?,
    val dayUsed: Map<String, Long>,
    val hourUsed: Map<String, Long>,
    val nightUsedMs: Long,
    /** Приложения, открытые в момент снимка: их время растёт до следующего снимка. */
    val openPackages: Set<String>,
)

/** Использование, по которому принимается решение о блокировке. */
data class BudgetUsage(
    val dayUsedMs: Long,
    val hourUsedMs: Long,
    val nightUsedMs: Long,
)

/** Сработавший лимит: [untilWall] — когда он снимается (полночь, начало часа или подъём). */
data class LimitBlock(
    val kind: Kind,
    val untilWall: Long,
) {
    enum class Kind { BEDTIME, DAILY, HOURLY }
}

/**
 * Бюджет времени по системной статистике: сколько использовано в эти сутки, в этот час и
 * этой ночью, и какой лимит (если есть) сейчас действует.
 * Приоритет: «Отбой» → дневной → часовой.
 */
object UsageBudget {

    fun hourStart(wallMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(wallMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    /** Binder-запрос к системе: вызывать на фоновом диспетчере. */
    fun snapshot(
        context: Context,
        nowWall: Long,
        nowElapsed: Long,
        zone: ZoneId,
        bedtime: BedtimeSettings,
    ): UsageSnapshot {
        val dayStart = BedtimePolicy.budgetDayStart(nowWall, zone, bedtime)
        val hourStart = hourStart(nowWall, zone)
        val night = BedtimePolicy.currentWindow(nowWall, zone, bedtime)
        val readFrom = minOf(dayStart, night?.startWall ?: dayStart)
        val sessions = UsageStatsReader.readSessions(context, readFrom, nowWall, TrackedApp.packageNames)

        val dayUsed = HashMap<String, Long>()
        val hourUsed = HashMap<String, Long>()
        var nightUsed = 0L
        for (session in sessions) {
            val dayOverlap = session.endMs - maxOf(session.startMs, dayStart)
            if (dayOverlap > 0L) dayUsed.merge(session.packageName, dayOverlap, Long::plus)
            val hourOverlap = session.endMs - maxOf(session.startMs, hourStart)
            if (hourOverlap > 0L) hourUsed.merge(session.packageName, hourOverlap, Long::plus)
            if (night != null) nightUsed += night.overlapMs(session.startMs, session.endMs)
        }
        return UsageSnapshot(
            takenAtWall = nowWall,
            takenAtElapsed = nowElapsed,
            dayStartWall = dayStart,
            hourStartWall = hourStart,
            nightStartWall = night?.startWall,
            dayUsed = dayUsed,
            hourUsed = hourUsed,
            nightUsedMs = nightUsed,
            openPackages = sessions.filter { it.isOpen }.map { it.packageName }.toSet(),
        )
    }

    fun decide(
        app: TrackedApp,
        usage: BudgetUsage,
        bedtime: BedtimeSettings,
        nowWall: Long,
        zone: ZoneId,
    ): LimitBlock? {
        val night = BedtimePolicy.currentWindow(nowWall, zone, bedtime)
        if (night != null && usage.nightUsedMs >= bedtime.allowanceMs) {
            return LimitBlock(LimitBlock.Kind.BEDTIME, night.endWall)
        }
        if (usage.dayUsedMs >= app.dailyLimitMs) {
            return LimitBlock(LimitBlock.Kind.DAILY, BedtimePolicy.dailyBlockUntil(nowWall, zone, bedtime))
        }
        val hourly = app.hourlyLimitMs ?: return null
        if (usage.hourUsedMs >= hourly) {
            return LimitBlock(LimitBlock.Kind.HOURLY, hourStart(nowWall, zone) + ChronoUnit.HOURS.duration.toMillis())
        }
        return null
    }
}
