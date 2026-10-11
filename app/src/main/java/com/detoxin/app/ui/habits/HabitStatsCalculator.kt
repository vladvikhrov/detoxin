package com.detoxin.app.ui.habits

import com.detoxin.app.data.local.dto.HabitWithLogs
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.entity.HabitAutoSource
import com.detoxin.app.data.local.entity.HabitLog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Чистая (без Android-зависимостей) аналитика привычек. Вызывается на Dispatchers.Default.
 *
 * Все интервалы — закрытые `[start, end]` в `epochDay`. «Активные» дни привычки начинаются
 * с дня её создания либо с первой отметки, если её поставили задним числом раньше создания:
 * новая привычка не штрафуется за дни, когда её ещё не было, а отметки за прошлые дни
 * учитываются в статистике.
 *
 * «Сегодня» не кэшируется: каждый вызов получает актуальную дату от ViewModel, а отметки за
 * прошлые дни приходят через тот же Flow логов и сразу пересчитывают стрик, неделю и темп.
 */
internal object HabitStatsCalculator {

    /** Привычка считается заброшенной, если не выполнялась столько дней подряд. */
    const val ABANDON_THRESHOLD_DAYS = 7

    /** Насколько далеко назад можно отметить привычку. */
    const val BACKFILL_WINDOW_DAYS = 60L

    /** Окно скользящей доли выполнения для линии динамики. */
    private const val ROLLING_WINDOW_DAYS = 7

    fun buildContent(
        habits: List<HabitWithLogs>,
        lastCompletions: Map<Long, Long>,
        cycleCounts: Map<Long, Int>,
        today: LocalDate,
        zone: ZoneId,
        locale: Locale,
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
                lastSevenDays = (DAYS_PER_WEEK - 1 downTo 0).map { offset ->
                    val date = today.minusDays(offset.toLong())
                    DayMark(
                        epochDay = date.toEpochDay(),
                        weekdayLabel = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                        isCompleted = date.toEpochDay() in completed,
                        isToday = offset == 0,
                    )
                },
                isAuto = HabitAutoSource.fromKey(item.habit.autoSource) != null,
            )
        }

        val abandoned = habits.mapNotNull { item ->
            val reference = lastCompletions[item.habit.id] ?: createdDay(item.habit, zone)
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
        val activeStart = activeStartDay(habit, completed, zone)
        val tooltipFormatter = DateTimeFormatter.ofPattern("d MMM, EE", locale)

        val rangeStart: LocalDate
        val trend: List<TrendPoint>
        when (range) {
            HabitRange.WEEK -> {
                rangeStart = startOfWeek(today)
                trend = List(DAYS_PER_WEEK) { offset ->
                    val date = rangeStart.plusDays(offset.toLong())
                    rollingPoint(
                        label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                        tooltipTitle = date.format(tooltipFormatter),
                        date = date,
                        completed = completed,
                        activeStart = activeStart,
                        todayDay = todayDay,
                    )
                }
            }
            HabitRange.MONTH -> {
                rangeStart = today.withDayOfMonth(1)
                trend = List(today.lengthOfMonth()) { offset ->
                    val date = rangeStart.plusDays(offset.toLong())
                    rollingPoint(
                        label = date.dayOfMonth.toString(),
                        tooltipTitle = date.format(tooltipFormatter),
                        date = date,
                        completed = completed,
                        activeStart = activeStart,
                        todayDay = todayDay,
                    )
                }
            }
            HabitRange.YEAR -> {
                rangeStart = today.withDayOfYear(1)
                trend = List(MONTHS_PER_YEAR) { index ->
                    val first = rangeStart.plusMonths(index.toLong())
                    val last = first.withDayOfMonth(first.lengthOfMonth())
                    val from = max(first.toEpochDay(), activeStart)
                    val to = min(last.toEpochDay(), todayDay)
                    TrendPoint(
                        label = first.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                        tooltipTitle = first.month.getDisplayName(TextStyle.FULL_STANDALONE, locale),
                        value = if (from > to) null else countInRange(completed, from, to).toFloat() / (to - from + 1),
                    )
                }
            }
        }

        val activeFrom = max(rangeStart.toEpochDay(), activeStart)
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
            trend = trend,
            weekdays = weekdays,
            completedInRange = hits.sum(),
            possibleInRange = occurrences.sum(),
            currentStreak = currentStreak(completed, todayDay),
            totalCompletions = totalCompletions,
            lastCompletedDay = lastCompletedDay,
            cycleProgress = cycleCount.coerceAtMost(habit.cycleLength),
            cycleLength = habit.cycleLength,
            cycleStartDay = habit.cycleResetEpochDay.takeIf { it > 0L },
            completedDays = completed,
            todayEpochDay = todayDay,
            backfillFromEpochDay = todayDay - BACKFILL_WINDOW_DAYS,
            isAuto = HabitAutoSource.fromKey(habit.autoSource) != null,
        )
    }

    /**
     * Самая ранняя дата, логи с которой нужны детальному экрану: начало года или недели,
     * окно отметок задним числом для календаря и ещё 6 дней на скользящую долю первого дня.
     */
    fun detailWindowStart(today: LocalDate): LocalDate =
        minOf(today.withDayOfYear(1), startOfWeek(today), today.minusDays(BACKFILL_WINDOW_DAYS))
            .minusDays(ROLLING_WINDOW_DAYS - 1L)

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
            val completed = completedDays(item.logs)
            val from = max(start, activeStartDay(item.habit, completed, zone))
            if (from > end) continue
            val activeDays = end - from + 1
            val expectedHere = activeDays * item.habit.targetDaysPerWeek / DAYS_PER_WEEK.toDouble()
            val done = countInRange(completed, from, end).toDouble()
            expected += expectedHere
            achieved += min(done, expectedHere)
        }
        return if (expected <= 0.0) null else (achieved / expected).toFloat()
    }

    // ---------- Общее ----------

    /**
     * Скользящая доля выполнения за 7 дней, заканчивающихся [date]: линия плавно растёт при
     * серии отметок и падает при пропусках. Будущие дни и дни до появления привычки — `null`.
     */
    private fun rollingPoint(
        label: String,
        tooltipTitle: String,
        date: LocalDate,
        completed: Set<Long>,
        activeStart: Long,
        todayDay: Long,
    ): TrendPoint {
        val day = date.toEpochDay()
        val from = max(day - (ROLLING_WINDOW_DAYS - 1), activeStart)
        val value = if (day > todayDay || from > day) {
            null
        } else {
            countInRange(completed, from, day).toFloat() / (day - from + 1)
        }
        return TrendPoint(label = label, tooltipTitle = tooltipTitle, value = value)
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

    /** День создания или более ранняя отметка, поставленная задним числом. */
    private fun activeStartDay(habit: Habit, completed: Set<Long>, zone: ZoneId): Long {
        val created = createdDay(habit, zone)
        val firstCompleted = completed.minOrNull() ?: return created
        return min(created, firstCompleted)
    }

    private fun startOfWeek(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    private const val DAYS_PER_WEEK = 7
    private const val MONTHS_PER_YEAR = 12
}
