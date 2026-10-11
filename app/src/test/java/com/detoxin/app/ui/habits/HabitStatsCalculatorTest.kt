package com.detoxin.app.ui.habits

import com.detoxin.app.data.local.dto.HabitWithLogs
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.entity.HabitAutoSource
import com.detoxin.app.data.local.entity.HabitLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class HabitStatsCalculatorTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val locale = Locale("ru")
    // Воскресенье: неделя Пн 05.10 — Вс 11.10 целиком в прошлом.
    private val today = LocalDate.of(2026, 10, 11)

    private fun habit(createdOn: LocalDate, target: Int = 7, autoSource: String? = null) = Habit(
        id = 1,
        title = "Прогулка",
        targetDaysPerWeek = target,
        colorHex = 0xFF1B7F4BL,
        createdAt = createdOn.atStartOfDay(zone).toInstant().toEpochMilli(),
        autoSource = autoSource,
    )

    private fun logs(vararg days: LocalDate) = days.mapIndexed { index, day ->
        HabitLog(id = index + 1L, habitId = 1, dateEpochDay = day.toEpochDay(), isCompleted = true)
    }

    @Test
    fun lastSevenDaysEndWithTodayAndReflectBackfill() {
        val item = HabitWithLogs(habit(today.minusDays(30)), logs(today.minusDays(3), today))
        val content = HabitStatsCalculator.buildContent(listOf(item), emptyMap(), emptyMap(), today, zone, locale)
        val days = content.habits.single().lastSevenDays
        assertEquals(7, days.size)
        assertEquals(today.toEpochDay(), days.last().epochDay)
        assertTrue(days.last().isToday)
        assertTrue(days[3].isCompleted)
        assertFalse(days[4].isCompleted)
    }

    @Test
    fun backfillBeforeCreationCountsAsActiveDays() {
        // Привычку создали сегодня и отметили три прошлых дня подряд.
        val item = HabitWithLogs(habit(today), logs(today.minusDays(2), today.minusDays(1), today))
        val content = HabitStatsCalculator.buildContent(listOf(item), emptyMap(), emptyMap(), today, zone, locale)
        assertEquals(3, content.habits.single().currentStreak)
        assertEquals(1f, content.overview.currentMonthRate!!, 0.0001f)
    }

    @Test
    fun rollingTrendRisesAndFallsAndBreaksBeforeHabitExisted() {
        val created = today.minusDays(3) // четверг 08.10
        val item = habit(created)
        // Отмечены чт и пт, пропущены сб и вс.
        val detail = HabitStatsCalculator.buildDetail(
            habit = item,
            logs = logs(created, created.plusDays(1)),
            range = HabitRange.WEEK,
            today = today,
            zone = zone,
            totalCompletions = 2,
            lastCompletedDay = created.plusDays(1).toEpochDay(),
            cycleCount = 2,
            locale = locale,
        )
        val values = detail.trend.map { it.value }
        assertNull(values[0]) // Пн — привычки ещё не было
        assertNull(values[2])
        assertEquals(1f, values[3]!!, 0.0001f) // Чт: 1 из 1
        assertEquals(1f, values[4]!!, 0.0001f) // Пт: 2 из 2
        assertEquals(2f / 3f, values[5]!!, 0.0001f) // Сб: 2 из 3
        assertEquals(0.5f, values[6]!!, 0.0001f) // Вс: 2 из 4
        assertEquals(today.toEpochDay() - HabitStatsCalculator.BACKFILL_WINDOW_DAYS, detail.backfillFromEpochDay)
        assertEquals(setOf(created.toEpochDay(), created.plusDays(1).toEpochDay()), detail.completedDays)
    }

    @Test
    fun futureDaysOfWeekHaveNoValue() {
        val wednesday = LocalDate.of(2026, 10, 7)
        val detail = HabitStatsCalculator.buildDetail(
            habit = habit(wednesday.minusDays(30), autoSource = HabitAutoSource.BEDTIME.key),
            logs = emptyList(),
            range = HabitRange.WEEK,
            today = wednesday,
            zone = zone,
            totalCompletions = 0,
            lastCompletedDay = null,
            cycleCount = 0,
            locale = locale,
        )
        val values = detail.trend.map { it.value }
        assertEquals(0f, values[2]!!, 0f)
        assertNull(values[3])
        assertNull(values[6])
        assertTrue(detail.isAuto)
    }

    @Test
    fun detailWindowCoversBackfillAndRollingWindow() {
        val start = HabitStatsCalculator.detailWindowStart(LocalDate.of(2026, 1, 10))
        assertEquals(LocalDate.of(2026, 1, 10).minusDays(HabitStatsCalculator.BACKFILL_WINDOW_DAYS + 6), start)
    }
}
