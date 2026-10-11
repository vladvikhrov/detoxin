package com.detoxin.app.ui.analytics

import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.data.local.entity.ProtectionGap
import com.detoxin.app.usage.BedtimeSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class NightStatsCalculatorTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val settings = BedtimeSettings(enabled = true, startMinute = 23 * 60 + 30, wakeMinute = 7 * 60, nightAllowanceMinutes = 0)
    private val minute = 60_000L

    private fun at(day: Int, hour: Int, minuteOfHour: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minuteOfHour).atZone(zone).toInstant().toEpochMilli()

    private fun log(packageName: String, start: Long, durationMs: Long) = AppUsageLog(
        packageName = packageName,
        sessionStartTime = start,
        durationMs = durationMs,
        dateEpochDay = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(start), zone).toEpochDay(),
    )

    @Test
    fun aggregatesNightUsageBedtimeAndGaps() {
        val now = at(11, 12)
        val logs = listOf(
            // Ночь 10→11: TikTok до 01:30 (с 23:00, внутри окна только 2 часа), YouTube 10 минут.
            log(TrackedApp.TIKTOK.packageName, at(10, 23), 150 * minute),
            log(TrackedApp.YOUTUBE.packageName, at(11, 0, 10), 10 * minute),
            // Дневное использование в окно не попадает.
            log(TrackedApp.TIKTOK.packageName, at(10, 15), 30 * minute),
        )
        val gaps = listOf(ProtectionGap(id = 1, startedAtWall = at(9, 23, 50), endedAtWall = at(10, 0, 20)))

        val stats = NightStatsCalculator.build(settings, nights = 7, logs = logs, gaps = gaps, nowWall = now, zone = zone, locale = Locale("ru"))

        assertEquals(7, stats.points.size)
        val lastNight = stats.points.last()
        assertEquals(120 * minute, lastNight.tikTokMs)
        assertEquals(10 * minute, lastNight.youTubeMs)
        // Последняя завершённая ночь — грязная, ночь 9→10 с выключенной защитой тоже.
        assertEquals(7, stats.completedNights)
        assertEquals(5, stats.cleanNights)
        assertEquals(1, stats.nightsWithGaps)
        assertEquals("23:30–07:00", stats.windowLabel)
    }

    @Test
    fun averageBedtimeUsesLastUseOrWindowStart() {
        val now = at(11, 12)
        // Одна ночь закончилась в 01:30, остальные шесть — чистые (отбой в 23:30).
        val logs = listOf(log(TrackedApp.TIKTOK.packageName, at(11, 1), 30 * minute))
        val stats = NightStatsCalculator.build(settings, nights = 7, logs = logs, gaps = emptyList(), nowWall = now, zone = zone, locale = Locale("ru"))
        // (120 мин + 6 × 0) / 7 ≈ 17 мин после 23:30.
        assertEquals("23:47", stats.averageBedtime)
        assertEquals("23:30", stats.previousAverageBedtime)
    }

    @Test
    fun nightCountFollowsRange() {
        assertEquals(7, NightStatsCalculator.nightCount(AnalyticsRange.DAY))
        assertEquals(7, NightStatsCalculator.nightCount(AnalyticsRange.WEEK))
        assertEquals(30, NightStatsCalculator.nightCount(AnalyticsRange.MONTH))
    }
}
