package com.detoxin.app.ui.analytics

import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.dto.HabitWithLogs
import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.data.local.entity.BlockType
import com.detoxin.app.data.local.entity.BlockedAttempt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Чистая (без Android-зависимостей) агрегация сырых строк Room в модели графиков.
 * Вызывается на Dispatchers.Default из [AnalyticsViewModel].
 */
internal object AnalyticsAggregator {

    private const val HOURS_PER_DAY = 24
    private const val DAYS_PER_WEEK = 7

    /** Лимит на час в режиме «День»: часовой лимит TikTok (15 минут). */
    val HOURLY_LIMIT_MS: Long = TrackedApp.TIKTOK.hourlyLimitMs ?: (15L * 60L * 1_000L)

    /** Дневной лимит в режимах «Неделя» и «Месяц»: суммарный бюджет TikTok (1 ч) + YouTube (1.5 ч). */
    val DAILY_LIMIT_MS: Long = TrackedApp.combinedDailyLimitMs

    private val tikTokPackages = setOf(TrackedApp.TIKTOK.packageName, "com.ss.android.ugc.trill")
    private val youTubePackage = TrackedApp.YOUTUBE.packageName

    /** Типы, означающие «спасённое открытие» бесконечной ленты. */
    private val savedOpenTypes = setOf(BlockType.SHORTS, BlockType.VK_CLIPS, BlockType.VK_FEED)

    fun aggregate(
        range: AnalyticsRange,
        startDay: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        usageLogs: List<AppUsageLog>,
        attempts: List<BlockedAttempt>,
        habits: List<HabitWithLogs>,
    ): AnalyticsUiState {
        val locale = Locale.getDefault()
        val savedAttempts = attempts.filter { it.blockType in savedOpenTypes }

        val usage = buildUsage(range, startDay, zone, locale, usageLogs)
        val blocks = buildBlocks(range, startDay, zone, locale, savedAttempts)
        val heatmap = buildHeatmap(startDay, today, zone, habits)

        val totalScreenTime = usage.bars.sumOf { it.totalMs }
        if (totalScreenTime == 0L && savedAttempts.isEmpty() && habits.isEmpty()) {
            return AnalyticsUiState.Empty(range)
        }

        val ratios = heatmap.weeks.flatten().filter { it.inRange }.mapNotNull { it.completionRatio }
        return AnalyticsUiState.Success(
            range = range,
            summary = AnalyticsSummary(
                totalScreenTimeMs = totalScreenTime,
                totalBlocked = savedAttempts.size,
                averageHabitCompletion = if (ratios.isEmpty()) null else ratios.average().toFloat(),
            ),
            usage = usage,
            blocks = blocks,
            heatmap = heatmap,
        )
    }

    // ---------- Экранное время ----------

    private fun buildUsage(
        range: AnalyticsRange,
        startDay: LocalDate,
        zone: ZoneId,
        locale: Locale,
        logs: List<AppUsageLog>,
    ): UsageChartData {
        val bucketCount = bucketCount(range)
        val tikTok = LongArray(bucketCount)
        val youTube = LongArray(bucketCount)
        val other = LongArray(bucketCount)

        for (log in logs) {
            val index = if (range == AnalyticsRange.DAY) {
                Instant.ofEpochMilli(log.sessionStartTime).atZone(zone).hour
            } else {
                (log.dateEpochDay - startDay.toEpochDay()).toInt()
            }
            if (index !in 0 until bucketCount) continue
            when (log.packageName) {
                in tikTokPackages -> tikTok[index] += log.durationMs
                youTubePackage -> youTube[index] += log.durationMs
                else -> other[index] += log.durationMs
            }
        }

        val bars = List(bucketCount) { index ->
            UsageBar(
                label = axisLabel(range, startDay, index, locale),
                tikTokMs = tikTok[index],
                youTubeMs = youTube[index],
                otherMs = other[index],
            )
        }
        val limit = if (range == AnalyticsRange.DAY) HOURLY_LIMIT_MS else DAILY_LIMIT_MS
        return UsageChartData(bars = bars, limitMs = limit)
    }

    // ---------- Спасённые открытия ----------

    private fun buildBlocks(
        range: AnalyticsRange,
        startDay: LocalDate,
        zone: ZoneId,
        locale: Locale,
        attempts: List<BlockedAttempt>,
    ): List<BlockPoint> {
        val bucketCount = bucketCount(range)
        val counts = IntArray(bucketCount)
        for (attempt in attempts) {
            val index = if (range == AnalyticsRange.DAY) {
                Instant.ofEpochMilli(attempt.timestamp).atZone(zone).hour
            } else {
                (attempt.dateEpochDay - startDay.toEpochDay()).toInt()
            }
            if (index in 0 until bucketCount) counts[index]++
        }
        val dateFormatter = DateTimeFormatter.ofPattern("d MMM, EE", locale)
        return List(bucketCount) { index ->
            val title = if (range == AnalyticsRange.DAY) {
                "%02d:00–%02d:00".format(locale, index, (index + 1) % HOURS_PER_DAY)
            } else {
                startDay.plusDays(index.toLong()).format(dateFormatter)
            }
            BlockPoint(
                axisLabel = axisLabel(range, startDay, index, locale),
                tooltipTitle = title,
                count = counts[index],
            )
        }
    }

    // ---------- Тепловая карта привычек ----------

    private fun buildHeatmap(
        startDay: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        habits: List<HabitWithLogs>,
    ): HeatmapData {
        val firstMonday = startDay.minusDays((startDay.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        val lastSunday = today.plusDays((DayOfWeek.SUNDAY.value - today.dayOfWeek.value).toLong())
        val weekCount = ((lastSunday.toEpochDay() - firstMonday.toEpochDay() + 1) / DAYS_PER_WEEK).toInt()

        val habitCreatedDay = habits.associate { item ->
            item.habit.id to Instant.ofEpochMilli(item.habit.createdAt).atZone(zone).toLocalDate().toEpochDay()
        }
        // Число выполненных привычек по дню (одна запись на привычку в день гарантирована индексом).
        val completedByDay = HashMap<Long, Int>()
        for (item in habits) {
            for (log in item.logs) {
                if (log.isCompleted) completedByDay.merge(log.dateEpochDay, 1, Int::plus)
            }
        }

        val weeks = List(weekCount) { week ->
            List(DAYS_PER_WEEK) { dayOfWeek ->
                val date = firstMonday.plusDays((week * DAYS_PER_WEEK + dayOfWeek).toLong())
                val epochDay = date.toEpochDay()
                val inRange = !date.isBefore(startDay) && !date.isAfter(today)
                val activeHabits = habitCreatedDay.values.count { it <= epochDay }
                val ratio = if (!inRange || activeHabits == 0) {
                    null
                } else {
                    ((completedByDay[epochDay] ?: 0).toFloat() / activeHabits).coerceIn(0f, 1f)
                }
                HeatmapCell(dateEpochDay = epochDay, completionRatio = ratio, inRange = inRange)
            }
        }
        return HeatmapData(weeks)
    }

    // ---------- Общее ----------

    private fun bucketCount(range: AnalyticsRange): Int =
        if (range == AnalyticsRange.DAY) HOURS_PER_DAY else range.days

    private fun axisLabel(range: AnalyticsRange, startDay: LocalDate, index: Int, locale: Locale): String =
        when (range) {
            AnalyticsRange.DAY -> "%02d".format(locale, index)
            AnalyticsRange.WEEK -> startDay.plusDays(index.toLong()).dayOfWeek
                .getDisplayName(TextStyle.SHORT, locale)
            AnalyticsRange.MONTH -> startDay.plusDays(index.toLong()).dayOfMonth.toString()
        }
}
