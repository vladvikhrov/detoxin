package com.detoxin.app.ui.habits

import com.detoxin.app.data.local.dto.HabitWithLogs
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.entity.HabitLog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Чистая (без Android-зависимостей) аналитика привычек. Вызывается на Dispatchers.Default.
 *
 * Все интервалы — закрытые `[start, end]` в `epochDay`. «Активные» дни привычки начинаются
 * с дня её создания: новая привычка не штрафуется за дни, когда её ещё не было.
 */
internal object HabitStatsCalculator {

    /** Привычка считается заброшенной, если не выполнялась столько дней подряд. */
    const val ABANDON_THRESHOLD_DAYS = 7

    fun buildContent(
        habits: List<HabitWithLogs>,
        lastCompletions: Map<Long, Long>,
        cycleCounts: Map<Long, Int>,
        today: LocalDate,
        zone: ZoneId,
    ): HabitsUiState.Content {
        val todayDay = today.toEpochDay()
        val weekStart = startOfWeek(today).toEpochDay()

        val items = habits.map { item ->
            val completed = completedDays(item.logs)
            HabitItemUi(
                id = item.habit.id,
                title = item.habit.title,
                colorHex = item.habit.colorHex,
                targetDaysPerWeek = item.habit.targetDaysPerWeek,
                completedToday = todayDay in completed,
                currentStreak = currentStreak(completed, todayDay),
                completedThisWeek = countInRange(completed, weekStart, todayDay),
                cycleProgress = (cycleCounts[item.habit.id] ?: 0).coerceAtMost(item.habit.cycleLength),
                cycleLength = item.habit.cycleLength,
            )
        }

        val abandoned = habits.mapNotNull { item ->
            val createdDay = createdDay(item.habit, zone)
            val reference = lastCompletions[item.habit.id] ?: createdDay
            val idle = (todayDay - reference).toInt()
            if (idle >= ABANDON_THRESHOLD_DAYS) {
                AbandonedHabit(item.habit.id, item.habit.title, idle)
            } else {
                null
            }
        }.sortedByDescending { it.daysSinceLastCompletion }

        return HabitsUiState.Content(
            overview = pace(habits, today, zone),
            abandoned = abandoned,
            habits = items,
        )
    }

    fun buildDetail(
        habit: Habit,
        logs: List<HabitLog>,
        range: HabitRange,
        today: LocalDate,
        zone: ZoneId,
        totalCompletions: Int,
        lastCompletedDay: Long?,
        cycleCount: Int,
        locale: Locale,
    ): HabitDetailUiState {
        val completed = completedDays(logs)
        val todayDay = today.toEpochDay()
        val createdDay = createdDay(habit, zone)

        val rangeStart: LocalDate
        val bars: List<TrendBar>
        when (range) {
            HabitRange.WEEK -> {
                rangeStart = startOfWeek(today)
                bars = List(DAYS_PER_WEEK) { offset ->
                    val date = rangeStart.plusDays(offset.toLong())
                    dayBar(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), date, completed, todayDay)
                }
            }
            HabitRange.MONTH -> {
                rangeStart = today.withDayOfMonth(1)
                bars = List(today.lengthOfMonth()) { offset ->
                    val date = rangeStart.plusDays(offset.toLong())
                    dayBar(date.dayOfMonth.toString(), date, completed, todayDay)
                }
            }
            HabitRange.YEAR -> {
                rangeStart = today.withDayOfYear(1)
                bars = List(MONTHS_PER_YEAR) { index ->
                    val first = rangeStart.plusMonths(index.toLong())
                    val last = first.withDayOfMonth(first.lengthOfMonth())
                    val from = max(first.toEpochDay(), createdDay)
                    val to = min(last.toEpochDay(), todayDay)
                    val value = if (from > to) 0f else countInRange(completed, from, to).toFloat() / (to - from + 1)
                    TrendBar(
                        label = first.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                        value = value,
                        isFuture = first.toEpochDay() > todayDay,
                    )
                }
            }
        }

        val activeFrom = max(rangeStart.toEpochDay(), createdDay)
        val occurrences = IntArray(DAYS_PER_WEEK)
        val hits = IntArray(DAYS_PER_WEEK)
        var day = activeFrom
        while (day <= todayDay) {
            val index = LocalDate.ofEpochDay(day).dayOfWeek.value - 1
            occurrences[index]++
            if (day in completed) hits[index]++
            day++
        }
        val weekdays = DayOfWeek.entries.map { dayOfWeek ->
            val index = dayOfWeek.value - 1
            WeekdayStat(
                label = dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                ratio = if (occurrences[index] == 0) null else hits[index].toFloat() / occurrences[index],
            )
        }

        return HabitDetailUiState(
            habitId = habit.id,
            title = habit.title,
            colorHex = habit.colorHex,
            targetDaysPerWeek = habit.targetDaysPerWeek,
            range = range,
            bars = bars,
            weekdays = weekdays,
            completedInRange = hits.sum(),
            possibleInRange = occurrences.sum(),
            currentStreak = currentStreak(completed, todayDay),
            totalCompletions = totalCompletions,
            lastCompletedDay = lastCompletedDay,
            cycleProgress = cycleCount.coerceAtMost(habit.cycleLength),
            cycleLength = habit.cycleLength,
            cycleStartDay = habit.cycleResetEpochDay.takeIf { it > 0L },
        )
    }

    /** Самая ранняя дата, логи с которой нужны детальному экрану для любого диапазона. */
    fun detailWindowStart(today: LocalDate): LocalDate =
        minOf(today.withDayOfYear(1), startOfWeek(today))

    // ---------- Темп месяц-к-месяцу ----------

    private fun pace(habits: List<HabitWithLogs>, today: LocalDate, zone: ZoneId): PaceOverview {
        val currentStart = today.withDayOfMonth(1)
        val previousStart = currentStart.minusMonths(1)
        val previousEnd = currentStart.minusDays(1)

        val current = periodRate(habits, currentStart.toEpochDay(), today.toEpochDay(), zone)
        val previous = periodRate(habits, previousStart.toEpochDay(), previousEnd.toEpochDay(), zone)
        val delta = if (current != null && previous != null) ((current - previous) * 100f).roundToInt() else null
        return PaceOverview(currentMonthRate = current, previousMonthRate = previous, deltaPoints = delta)
    }

    /**
     * Доля выполнения нормы: для каждой привычки ожидается `activeDays × target / 7` отметок,
     * перевыполнение одной привычки не компенсирует отставание другой.
     */
    private fun periodRate(habits: List<HabitWithLogs>, start: Long, end: Long, zone: ZoneId): Float? {
        var expected = 0.0
        var achieved = 0.0
        for (item in habits) {
            val from = max(start, createdDay(item.habit, zone))
            if (from > end) continue
            val activeDays = end - from + 1
            val expectedHere = activeDays * item.habit.targetDaysPerWeek / DAYS_PER_WEEK.toDouble()
            val done = countInRange(completedDays(item.logs), from, end).toDouble()
            expected += expectedHere
            achieved += min(done, expectedHere)
        }
        return if (expected <= 0.0) null else (achieved / expected).toFloat()
    }

    // ---------- Общее ----------

    private fun dayBar(label: String, date: LocalDate, completed: Set<Long>, todayDay: Long): TrendBar {
        val day = date.toEpochDay()
        return TrendBar(label = label, value = if (day in completed) 1f else 0f, isFuture = day > todayDay)
    }

    /** Стрик жив, если сегодня ещё не отмечено, но вчера выполнено. */
    private fun currentStreak(completed: Set<Long>, todayDay: Long): Int {
        var day = if (todayDay in completed) todayDay else todayDay - 1
        var streak = 0
        while (day in completed) {
            streak++
            day--
        }
        return streak
    }

    private fun completedDays(logs: List<HabitLog>): Set<Long> =
        logs.asSequence().filter { it.isCompleted }.map { it.dateEpochDay }.toHashSet()

    private fun countInRange(completed: Set<Long>, start: Long, end: Long): Int =
        completed.count { it in start..end }

    private fun createdDay(habit: Habit, zone: ZoneId): Long =
        Instant.ofEpochMilli(habit.createdAt).atZone(zone).toLocalDate().toEpochDay()

    private fun startOfWeek(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    private const val DAYS_PER_WEEK = 7
    private const val MONTHS_PER_YEAR = 12
}
