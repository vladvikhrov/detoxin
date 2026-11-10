package com.detoxin.app.ui.relapse

import androidx.compose.runtime.Immutable
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.data.local.entity.ProtectionGap
import com.detoxin.app.data.local.entity.ProtectionGapReason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Отрезок внутри суток в долях `[0, 1]` (0 — полночь, 1 — следующая полночь). */
@Immutable
data class DaySpan(val start: Float, val end: Float)

/** Строка временной шкалы: один календарный день. */
@Immutable
data class RelapseDayRow(
    val label: String,
    val isToday: Boolean,
    /** Когда защита была выключена. */
    val gaps: List<DaySpan>,
    /** Когда в это время были открыты отслеживаемые приложения. */
    val bypass: List<DaySpan>,
)

/** Один срыв в списке под графиком. */
@Immutable
data class RelapseEntry(
    val id: Long,
    val title: String,
    val period: String,
    val durationMs: Long,
    val bypassMs: Long,
    /** «TikTok 42 мин, YouTube 10 мин» по убыванию; пусто — в обход ничего не открывалось. */
    val bypassByApp: List<Pair<String, Long>>,
    val isOngoing: Boolean,
)

@Immutable
data class RelapseUiState(
    val days: Int,
    val rows: List<RelapseDayRow>,
    val entries: List<RelapseEntry>,
    val totalUnprotectedMs: Long,
    val totalBypassMs: Long,
    val relapseCount: Int,
    val longestMs: Long,
    /** Сколько полных дней подряд (включая сегодня) без единого срыва. */
    val cleanDays: Int,
)

/**
 * «Стена стыда»: сводка периодов без защиты ([ProtectionGap]) и того, что было открыто в обход.
 *
 * Чистая функция. Время «в обход» — пересечение сессий из `app_usage_logs` (их пишет синхронизация
 * системной статистики, которая работает и при выключенной службе) с периодами разрывов.
 * Разрывы, уходящие за окно или за «сейчас», обрезаются; открытый разрыв длится до «сейчас».
 */
internal object RelapseCalculator {

    fun build(
        gaps: List<ProtectionGap>,
        logs: List<AppUsageLog>,
        nowWall: Long,
        today: LocalDate,
        zone: ZoneId,
        days: Int,
        locale: Locale,
    ): RelapseUiState {
        val firstDay = today.minusDays(days - 1L)
        val windowStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()

        val clipped = gaps.mapNotNull { gap ->
            val start = maxOf(gap.startedAtWall, windowStart)
            val end = minOf(gap.endedAtWall ?: nowWall, nowWall)
            if (end > start) ClippedGap(gap, start, end) else null
        }.sortedBy { it.start }

        val sessions = logs.map { Session(it.packageName, it.sessionStartTime, it.sessionStartTime + it.durationMs) }

        val rows = (0 until days).map { offset ->
            val date = today.minusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val dayGaps = clipped.mapNotNull { span(it.start, it.end, dayStart, dayEnd) }
            val dayBypass = clipped.flatMap { gap ->
                sessions.mapNotNull { session ->
                    val start = maxOf(session.start, gap.start)
                    val end = minOf(session.end, gap.end)
                    if (end > start) span(start, end, dayStart, dayEnd) else null
                }
            }
            RelapseDayRow(
                label = date.format(DateTimeFormatter.ofPattern("d MMM", locale)),
                isToday = offset == 0,
                gaps = dayGaps,
                bypass = dayBypass,
            )
        }

        val clockFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", locale)
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm", locale)
        val entries = clipped.sortedByDescending { it.start }.map { gap ->
            val perApp = LinkedHashMap<String, Long>()
            for (session in sessions) {
                val overlap = minOf(session.end, gap.end) - maxOf(session.start, gap.start)
                if (overlap > 0L) perApp.merge(appName(session.packageName), overlap, Long::plus)
            }
            val from = Instant.ofEpochMilli(gap.start).atZone(zone)
            val to = Instant.ofEpochMilli(gap.end).atZone(zone)
            val ongoing = gap.source.endedAtWall == null || gap.source.endedAtWall > nowWall
            val toLabel = if (from.toLocalDate() == to.toLocalDate()) to.format(timeFormat) else to.format(clockFormat)
            RelapseEntry(
                id = gap.source.id,
                title = ProtectionGapReason.fromKey(gap.source.reason).title,
                period = from.format(clockFormat) + " – " + if (ongoing) "сейчас" else toLabel,
                durationMs = gap.end - gap.start,
                bypassMs = perApp.values.sum(),
                bypassByApp = perApp.entries.sortedByDescending { it.value }.map { it.key to it.value },
                isOngoing = ongoing,
            )
        }

        val daysWithRelapse = clipped.flatMap { gap ->
            val from = Instant.ofEpochMilli(gap.start).atZone(zone).toLocalDate()
            val to = Instant.ofEpochMilli(gap.end - 1L).atZone(zone).toLocalDate()
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        }.toSet()
        var cleanDays = 0
        while (cleanDays < days && today.minusDays(cleanDays.toLong()) !in daysWithRelapse) cleanDays++

        return RelapseUiState(
            days = days,
            rows = rows,
            entries = entries,
            totalUnprotectedMs = clipped.sumOf { it.end - it.start },
            totalBypassMs = entries.sumOf { it.bypassMs },
            relapseCount = clipped.size,
            longestMs = clipped.maxOfOrNull { it.end - it.start } ?: 0L,
            cleanDays = cleanDays,
        )
    }

    /** Суммарное время сессий [logs] внутри разрывов [gaps] на отрезке `[fromWall, toWall)`. */
    fun bypassMs(gaps: List<ProtectionGap>, logs: List<AppUsageLog>, fromWall: Long, toWall: Long): Long {
        var total = 0L
        for (gap in gaps) {
            val gapStart = maxOf(gap.startedAtWall, fromWall)
            val gapEnd = minOf(gap.endedAtWall ?: toWall, toWall)
            if (gapEnd <= gapStart) continue
            for (log in logs) {
                val overlap = minOf(log.sessionStartTime + log.durationMs, gapEnd) - maxOf(log.sessionStartTime, gapStart)
                if (overlap > 0L) total += overlap
            }
        }
        return total
    }

    private fun span(start: Long, end: Long, dayStart: Long, dayEnd: Long): DaySpan? {
        val from = maxOf(start, dayStart)
        val to = minOf(end, dayEnd)
        if (to <= from) return null
        // Длина суток берётся фактическая: в день перевода часов она не равна 24 часам.
        val length = (dayEnd - dayStart).coerceAtLeast(1L).toFloat()
        return DaySpan((from - dayStart) / length, (to - dayStart) / length)
    }

    private fun appName(packageName: String): String =
        TrackedApp.fromPackage(packageName)?.displayName ?: packageName

    private class ClippedGap(val source: ProtectionGap, val start: Long, val end: Long)
    private class Session(val packageName: String, val start: Long, val end: Long)

}
