package com.detoxin.app.ui.analytics

import androidx.compose.runtime.Immutable
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.data.local.entity.ProtectionGap
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.BedtimeSettings
import com.detoxin.app.usage.NightWindow
import com.detoxin.app.util.formatMinuteOfDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Одна ночь на графике «Ночное время». Подпись — дата вечера, с которого ночь началась. */
@Immutable
data class NightPoint(
    val axisLabel: String,
    val tooltipTitle: String,
    val tikTokMs: Long,
    val youTubeMs: Long,
)

/**
 * Карточка «Ночное время» (только при включённом «Отбое»).
 *
 * @property averageBedtime «Отбой фактически»: средний момент, после которого отслеживаемые
 * приложения ночью больше не открывались (для ночи без использования — начало окна).
 * `null` — ещё нет ни одной завершённой ночи.
 * @property previousAverageBedtime то же за предыдущий такой же период.
 */
@Immutable
data class NightStatsUiState(
    val windowLabel: String,
    val allowanceMinutes: Int,
    val points: List<NightPoint>,
    val averageBedtime: String?,
    val previousAverageBedtime: String?,
    val previousPeriodLabel: String,
    val cleanNights: Int,
    val completedNights: Int,
    val nightsWithGaps: Int,
)

/**
 * Чистая агрегация ночной статистики из `app_usage_logs` и `protection_gaps`.
 * Вызывается на Dispatchers.Default из [AnalyticsViewModel].
 */
internal object NightStatsCalculator {

    private const val MS_PER_MINUTE = 60_000L
    private val tikTokPackages = setOf(TrackedApp.TIKTOK.packageName, "com.ss.android.ugc.trill")
    private val youTubePackage = TrackedApp.YOUTUBE.packageName

    /** Ночи для диапазона аналитики: месяц — 30, иначе — 7. */
    fun nightCount(range: AnalyticsRange): Int = if (range == AnalyticsRange.MONTH) 30 else 7

    /** Самое раннее окно, данные которого нужны (текущий период + предыдущий для сравнения). */
    fun firstWindow(nights: Int, settings: BedtimeSettings, nowWall: Long, zone: ZoneId): NightWindow {
        val latest = BedtimePolicy.latestStartedWindow(nowWall, zone, settings)
        return BedtimePolicy.windowStartingOn(latest.startDate.minusDays(2L * nights - 1), zone, settings)
    }

    fun build(
        settings: BedtimeSettings,
        nights: Int,
        logs: List<AppUsageLog>,
        gaps: List<ProtectionGap>,
        nowWall: Long,
        zone: ZoneId,
        locale: Locale,
    ): NightStatsUiState {
        val latest = BedtimePolicy.latestStartedWindow(nowWall, zone, settings)
        val current = (nights - 1 downTo 0).map { BedtimePolicy.windowStartingOn(latest.startDate.minusDays(it.toLong()), zone, settings) }
        val previous = (2 * nights - 1 downTo nights).map { BedtimePolicy.windowStartingOn(latest.startDate.minusDays(it.toLong()), zone, settings) }
        val currentNights = current.map { aggregate(it, logs, nowWall) }
        val previousNights = previous.map { aggregate(it, logs, nowWall) }

        val tooltipFormatter = DateTimeFormatter.ofPattern("d MMM", locale)
        val points = currentNights.map { night ->
            val date = night.window.startDate
            NightPoint(
                axisLabel = if (nights <= 7) date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) else date.dayOfMonth.toString(),
                tooltipTitle = "Ночь с ${date.format(tooltipFormatter)}",
                tikTokMs = night.tikTokMs,
                youTubeMs = night.youTubeMs,
            )
        }

        val completed = currentNights.filter { it.window.endWall <= nowWall }
        val withGaps = currentNights.count { night -> gaps.any { overlaps(it, night.window, nowWall) } }
        val clean = completed.count { night ->
            night.totalMs <= settings.allowanceMs + BedtimePolicy.AUTO_HABIT_GRACE_MS &&
                gaps.none { overlaps(it, night.window, nowWall) }
        }

        return NightStatsUiState(
            windowLabel = "${formatMinuteOfDay(settings.startMinute)}–${formatMinuteOfDay(settings.wakeMinute)}",
            allowanceMinutes = settings.nightAllowanceMinutes,
            points = points,
            averageBedtime = averageBedtime(completed, settings),
            previousAverageBedtime = averageBedtime(previousNights.filter { it.window.endWall <= nowWall }, settings),
            previousPeriodLabel = if (nights <= 7) "неделю назад" else "месяц назад",
            cleanNights = clean,
            completedNights = completed.size,
            nightsWithGaps = withGaps,
        )
    }

    private class NightUsage(
        val window: NightWindow,
        val tikTokMs: Long,
        val youTubeMs: Long,
        val totalMs: Long,
        /** Конец последней сессии внутри окна; `null` — ночью приложения не открывались. */
        val lastUseWall: Long?,
    )

    private fun aggregate(window: NightWindow, logs: List<AppUsageLog>, nowWall: Long): NightUsage {
        var tikTok = 0L
        var youTube = 0L
        var total = 0L
        var lastUse: Long? = null
        for (log in logs) {
            val start = log.sessionStartTime
            val end = start + log.durationMs
            val overlap = window.overlapMs(start, end)
            if (overlap <= 0L) continue
            total += overlap
            when (log.packageName) {
                in tikTokPackages -> tikTok += overlap
                youTubePackage -> youTube += overlap
            }
            val clippedEnd = minOf(end, window.endWall, nowWall)
            lastUse = maxOf(lastUse ?: clippedEnd, clippedEnd)
        }
        return NightUsage(window, tikTok, youTube, total, lastUse)
    }

    /** Средний «фактический отбой»: минуты от начала окна усредняются, затем переводятся в часы. */
    private fun averageBedtime(nights: List<NightUsage>, settings: BedtimeSettings): String? {
        if (nights.isEmpty()) return null
        val averageOffsetMinutes = nights
            .map { ((it.lastUseWall ?: it.window.startWall) - it.window.startWall) / MS_PER_MINUTE }
            .average()
            .roundToInt()
        val minuteOfDay = Math.floorMod(settings.startMinute + averageOffsetMinutes, BedtimeSettings.MINUTES_PER_DAY)
        return formatMinuteOfDay(minuteOfDay)
    }

    private fun overlaps(gap: ProtectionGap, window: NightWindow, nowWall: Long): Boolean {
        val gapEnd = gap.endedAtWall ?: nowWall
        return gap.startedAtWall < window.endWall && gapEnd > window.startWall
    }
}
