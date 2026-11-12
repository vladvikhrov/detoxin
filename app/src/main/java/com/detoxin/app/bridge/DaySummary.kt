package com.detoxin.app.bridge

import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.data.local.entity.BlockedAttempt
import com.detoxin.app.data.local.entity.ProtectionGap
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.BedtimeSettings
import com.detoxin.app.util.formatMinuteOfDay
import java.time.LocalDate
import java.time.ZoneId

/**
 * Сводка одного дня для дневника Retox (мост [SummaryProvider]).
 *
 * @property screenMinutes время в отслеживаемых приложениях за календарный день.
 * @property nightMinutes время в них же в окне «Отбоя», начавшемся вечером этого дня;
 *   `null` — «Отбой» выключен или ночь ещё не закончилась.
 * @property bedtimeKept ночь чистая (в пределах разрешённых минут и без разрывов защиты);
 *   `null` — как у [nightMinutes].
 * @property actualBedtime «Отбой фактически» — конец последней сессии в окне (или начало окна).
 * @property protectionGaps сколько разрывов защиты пересекло этот день.
 * @property blockedAttempts сколько раз Detoxin заблокировал ленту/приложение за день.
 */
data class DaySummary(
    val date: LocalDate,
    val screenMinutes: Int,
    val nightMinutes: Int?,
    val bedtimeKept: Boolean?,
    val actualBedtime: String?,
    val protectionGaps: Int,
    val blockedAttempts: Int,
)

/**
 * Чистый расчёт сводок. Настройки «Отбоя» берутся текущие для всех дней:
 * история их изменений не хранится, для дневника этого достаточно.
 */
internal object DaySummaryCalculator {

    private const val MS_PER_MINUTE = 60_000L

    fun build(
        from: LocalDate,
        to: LocalDate,
        logs: List<AppUsageLog>,
        attempts: List<BlockedAttempt>,
        gaps: List<ProtectionGap>,
        bedtime: BedtimeSettings,
        nowWall: Long,
        zone: ZoneId,
    ): List<DaySummary> {
        val byDay = logs.groupBy { it.dateEpochDay }
        val attemptsByDay = attempts.groupingBy { it.dateEpochDay }.eachCount()
        val nightsOn = bedtime.enabled && bedtime.hasWindow

        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { date ->
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val screenMs = byDay[date.toEpochDay()].orEmpty().sumOf { it.durationMs }
            val gapCount = gaps.count { overlaps(it, dayStart, dayEnd, nowWall) }

            var nightMinutes: Int? = null
            var kept: Boolean? = null
            var actual: String? = null
            if (nightsOn) {
                val window = BedtimePolicy.windowStartingOn(date, zone, bedtime)
                if (window.endWall <= nowWall) {
                    var nightMs = 0L
                    var lastUse: Long? = null
                    for (log in logs) {
                        val start = log.sessionStartTime
                        val end = start + log.durationMs
                        val overlap = window.overlapMs(start, end)
                        if (overlap <= 0L) continue
                        nightMs += overlap
                        val clippedEnd = minOf(end, window.endWall)
                        lastUse = maxOf(lastUse ?: clippedEnd, clippedEnd)
                    }
                    val nightGaps = gaps.any { overlaps(it, window.startWall, window.endWall, nowWall) }
                    nightMinutes = (nightMs / MS_PER_MINUTE).toInt()
                    kept = nightMs <= bedtime.allowanceMs + BedtimePolicy.AUTO_HABIT_GRACE_MS && !nightGaps
                    val offsetMinutes = (((lastUse ?: window.startWall) - window.startWall) / MS_PER_MINUTE).toInt()
                    actual = formatMinuteOfDay(Math.floorMod(bedtime.startMinute + offsetMinutes, BedtimeSettings.MINUTES_PER_DAY))
                }
            }

            DaySummary(
                date = date,
                screenMinutes = (screenMs / MS_PER_MINUTE).toInt(),
                nightMinutes = nightMinutes,
                bedtimeKept = kept,
                actualBedtime = actual,
                protectionGaps = gapCount,
                blockedAttempts = attemptsByDay[date.toEpochDay()] ?: 0,
            )
        }.toList()
    }

    private fun overlaps(gap: ProtectionGap, fromWall: Long, toWall: Long, nowWall: Long): Boolean {
        val gapEnd = gap.endedAtWall ?: nowWall
        return gap.startedAtWall < toWall && gapEnd > fromWall
    }
}
