package com.detoxin.app.usage

import com.detoxin.app.core.TrackedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UsageBudgetTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val bedtime = BedtimeSettings(enabled = true, startMinute = 23 * 60 + 30, wakeMinute = 7 * 60, nightAllowanceMinutes = 0)
    private val minute = 60_000L

    private fun at(day: Int, hour: Int, minuteOfHour: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minuteOfHour).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun bedtimeWithZeroAllowanceBlocksImmediately() {
        val block = UsageBudget.decide(TrackedApp.TIKTOK, BudgetUsage(0, 0, 0), bedtime, at(11, 1), zone)
        assertEquals(LimitBlock.Kind.BEDTIME, block!!.kind)
        assertEquals(at(11, 7), block.untilWall)
    }

    @Test
    fun nightAllowanceIsSharedAndThenBlocks() {
        val withAllowance = bedtime.copy(nightAllowanceMinutes = 15)
        assertNull(UsageBudget.decide(TrackedApp.TIKTOK, BudgetUsage(0, 0, 14 * minute), withAllowance, at(11, 1), zone))
        val block = UsageBudget.decide(TrackedApp.YOUTUBE, BudgetUsage(0, 0, 15 * minute), withAllowance, at(11, 1), zone)
        assertEquals(LimitBlock.Kind.BEDTIME, block!!.kind)
    }

    @Test
    fun bedtimeTakesPriorityOverDailyAndHourly() {
        val block = UsageBudget.decide(TrackedApp.TIKTOK, BudgetUsage(2 * 60 * minute, 30 * minute, 0), bedtime, at(11, 0, 30), zone)
        assertEquals(LimitBlock.Kind.BEDTIME, block!!.kind)
    }

    @Test
    fun dailyLimitBeforeBedtimeHoldsUntilWake() {
        val block = UsageBudget.decide(TrackedApp.TIKTOK, BudgetUsage(60 * minute, 0, 0), bedtime, at(10, 22), zone)
        assertEquals(LimitBlock.Kind.DAILY, block!!.kind)
        assertEquals(at(11, 7), block.untilWall)
    }

    @Test
    fun withoutBedtimeLimitsBehaveAsBefore() {
        val off = bedtime.copy(enabled = false)
        val daily = UsageBudget.decide(TrackedApp.TIKTOK, BudgetUsage(60 * minute, 0, 0), off, at(10, 22), zone)
        assertEquals(LimitBlock.Kind.DAILY, daily!!.kind)
        assertEquals(at(11, 0), daily.untilWall)
        val hourly = UsageBudget.decide(TrackedApp.TIKTOK, BudgetUsage(20 * minute, 15 * minute, 0), off, at(10, 22, 40), zone)
        assertEquals(LimitBlock.Kind.HOURLY, hourly!!.kind)
        assertEquals(at(10, 23), hourly.untilWall)
        assertNull(UsageBudget.decide(TrackedApp.YOUTUBE, BudgetUsage(20 * minute, 20 * minute, 0), off, at(10, 22), zone))
    }
}
