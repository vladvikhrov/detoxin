package com.detoxin.app.bridge

import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.data.local.entity.BlockType
import com.detoxin.app.data.local.entity.BlockedAttempt
import com.detoxin.app.data.local.entity.ProtectionGap
import com.detoxin.app.usage.BedtimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DaySummaryCalculatorTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val bedtime = BedtimeSettings(enabled = true, startMinute = 23 * 60, wakeMinute = 7 * 60, nightAllowanceMinutes = 5)
    private val d1 = LocalDate.of(2026, 11, 10)
    private val d2 = LocalDate.of(2026, 11, 11)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(date, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
    private fun log(date: LocalDate, h: Int, m: Int, minutes: Int) =
        AppUsageLog(packageName = "com.zhiliaoapp.musically", sessionStartTime = at(date, h, m), durationMs = minutes * 60_000L, dateEpochDay = date.toEpochDay())

    @Test
    fun screenTimeNightAndBedtime() {
        val logs = listOf(
            log(d1, 12, 0, 30),
            log(d1, 23, 10, 20), // 20 минут ночью, после 23:00 → отбой нарушен
            log(d2, 9, 0, 15),
        )
        val now = at(d2.plusDays(1), 12)
        val result = DaySummaryCalculator.build(d1, d2, logs, emptyList(), emptyList(), bedtime, now, zone)

        assertEquals(2, result.size)
        val first = result[0]
        assertEquals(50, first.screenMinutes)
        assertEquals(20, first.nightMinutes)
        assertEquals(false, first.bedtimeKept)
        assertEquals("23:30", first.actualBedtime)

        val second = result[1]
        assertEquals(15, second.screenMinutes)
        assertEquals(0, second.nightMinutes)
        assertEquals(true, second.bedtimeKept)
        assertEquals("23:00", second.actualBedtime)
    }

    @Test
    fun unfinishedNightAndDisabledBedtimeGiveNulls() {
        val now = at(d1, 23, 30) // ночь с 10-го ещё идёт
        val unfinished = DaySummaryCalculator.build(d1, d1, emptyList(), emptyList(), emptyList(), bedtime, now, zone).single()
        assertNull(unfinished.nightMinutes)
        assertNull(unfinished.bedtimeKept)

        val off = DaySummaryCalculator.build(d1, d1, emptyList(), emptyList(), emptyList(), bedtime.copy(enabled = false), at(d2, 12), zone).single()
        assertNull(off.bedtimeKept)
        assertNull(off.actualBedtime)
    }

    @Test
    fun gapsAndBlockedAttempts() {
        val gaps = listOf(ProtectionGap(startedAtWall = at(d1, 1), endedAtWall = at(d1, 2)))
        val attempts = listOf(
            BlockedAttempt(blockType = BlockType.SHORTS, timestamp = at(d1, 15), dateEpochDay = d1.toEpochDay()),
            BlockedAttempt(blockType = BlockType.VK_FEED, timestamp = at(d1, 16), dateEpochDay = d1.toEpochDay()),
        )
        val day = DaySummaryCalculator.build(d1, d1, emptyList(), attempts, gaps, bedtime, at(d2, 12), zone).single()
        assertEquals(1, day.protectionGaps)
        assertEquals(2, day.blockedAttempts)
    }

    @Test
    fun gapDuringNightBreaksBedtime() {
        val gaps = listOf(ProtectionGap(startedAtWall = at(d2, 2), endedAtWall = at(d2, 3)))
        val day = DaySummaryCalculator.build(d1, d1, emptyList(), emptyList(), gaps, bedtime, at(d2, 12), zone).single()
        assertEquals(false, day.bedtimeKept)
    }
}
