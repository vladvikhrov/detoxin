package com.detoxin.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BedtimePolicyTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val night = BedtimeSettings(enabled = true, startMinute = 23 * 60 + 30, wakeMinute = 7 * 60, nightAllowanceMinutes = 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ---------- Окно сна ----------

    @Test
    fun windowCrossingMidnightContainsLateNight() {
        val window = BedtimePolicy.currentWindow(at(2026, 10, 11, 1), zone, night)
        assertNotNull(window)
        assertEquals(LocalDate.of(2026, 10, 10), window!!.startDate)
        assertEquals(at(2026, 10, 10, 23, 30), window.startWall)
        assertEquals(at(2026, 10, 11, 7), window.endWall)
    }

    @Test
    fun windowBoundariesAreStartInclusiveEndExclusive() {
        assertTrue(BedtimePolicy.isInBedtime(at(2026, 10, 10, 23, 30), zone, night))
        assertFalse(BedtimePolicy.isInBedtime(at(2026, 10, 11, 7), zone, night))
        assertFalse(BedtimePolicy.isInBedtime(at(2026, 10, 10, 22), zone, night))
    }

    @Test
    fun disabledOrZeroLengthWindowNeverBlocks() {
        assertFalse(BedtimePolicy.isInBedtime(at(2026, 10, 11, 1), zone, night.copy(enabled = false)))
        assertFalse(BedtimePolicy.isInBedtime(at(2026, 10, 11, 1), zone, night.copy(wakeMinute = night.startMinute)))
    }

    @Test
    fun windowAfterMidnightDoesNotCrossDays() {
        val late = night.copy(startMinute = 60, wakeMinute = 7 * 60)
        val window = BedtimePolicy.currentWindow(at(2026, 10, 11, 3), zone, late)
        assertEquals(at(2026, 10, 11, 1), window!!.startWall)
        assertEquals(at(2026, 10, 11, 7), window.endWall)
        assertFalse(BedtimePolicy.isInBedtime(at(2026, 10, 10, 23, 45), zone, late))
    }

    @Test
    fun windowFollowsDaylightSavingShift() {
        val berlin = ZoneId.of("Europe/Berlin")
        // В ночь на 29.03.2026 часы переводятся вперёд: окно 23:30–07:00 длится 6.5 часа, а не 7.5.
        val window = BedtimePolicy.windowStartingOn(LocalDate.of(2026, 3, 28), berlin, night)
        assertEquals(6.5 * 60 * 60 * 1000, (window.endWall - window.startWall).toDouble(), 0.0)
    }

    // ---------- Сутки бюджета и дневной лимит ----------

    @Test
    fun budgetDayDoesNotResetAtMidnightDuringBedtime() {
        assertEquals(at(2026, 10, 10, 0), BedtimePolicy.budgetDayStart(at(2026, 10, 11, 1), zone, night))
        assertEquals(at(2026, 10, 11, 0), BedtimePolicy.budgetDayStart(at(2026, 10, 11, 12), zone, night))
        assertEquals(at(2026, 10, 11, 0), BedtimePolicy.budgetDayStart(at(2026, 10, 11, 1), zone, night.copy(enabled = false)))
    }

    @Test
    fun dailyBlockLastsUntilWakeWhenMidnightFallsIntoBedtime() {
        assertEquals(at(2026, 10, 11, 7), BedtimePolicy.dailyBlockUntil(at(2026, 10, 10, 22), zone, night))
        assertEquals(at(2026, 10, 11, 7), BedtimePolicy.dailyBlockUntil(at(2026, 10, 11, 2), zone, night))
        assertEquals(at(2026, 10, 11, 0), BedtimePolicy.dailyBlockUntil(at(2026, 10, 10, 22), zone, night.copy(enabled = false)))
    }

    // ---------- Напоминание и подъём ----------

    @Test
    fun reminderIsHalfHourBeforeNextBedtime() {
        assertEquals(at(2026, 10, 10, 23), BedtimePolicy.nextReminderAt(at(2026, 10, 10, 22), zone, night))
        // Напоминание на сегодня уже прошло — следующее завтра.
        assertEquals(at(2026, 10, 11, 23), BedtimePolicy.nextReminderAt(at(2026, 10, 10, 23, 10), zone, night))
        assertEquals(at(2026, 10, 11, 23), BedtimePolicy.nextReminderAt(at(2026, 10, 11, 2), zone, night))
    }

    @Test
    fun nextWakeIsCurrentWindowEndOrNextOne() {
        assertEquals(at(2026, 10, 11, 7), BedtimePolicy.nextWakeAt(at(2026, 10, 11, 2), zone, night))
        assertEquals(at(2026, 10, 12, 7), BedtimePolicy.nextWakeAt(at(2026, 10, 11, 12), zone, night))
    }

    // ---------- Замок и отложенное ослабление ----------

    @Test
    fun lockCoversBedtimeAndHalfHourBeforeIt() {
        assertNull(BedtimePolicy.lockEnd(at(2026, 10, 10, 22), zone, night))
        assertEquals(at(2026, 10, 11, 7), BedtimePolicy.lockEnd(at(2026, 10, 10, 23, 10), zone, night))
        assertEquals(at(2026, 10, 11, 7), BedtimePolicy.lockEnd(at(2026, 10, 11, 2), zone, night))
        assertNull(BedtimePolicy.lockEnd(at(2026, 10, 11, 2), zone, night.copy(enabled = false)))
    }

    @Test
    fun changesOutsideLockApplyImmediately() {
        val config = BedtimeConfig(night, null, 0L)
        val result = BedtimePolicy.applyChange(config, night.copy(enabled = false), at(2026, 10, 10, 15), zone)
        assertEquals(night.copy(enabled = false), result.active)
        assertNull(result.pending)
    }

    @Test
    fun disablingAtNightIsDeferredUntilWake() {
        val now = at(2026, 10, 11, 1)
        val result = BedtimePolicy.applyChange(BedtimeConfig(night, null, 0L), night.copy(enabled = false), now, zone)
        assertEquals(night, result.active)
        assertEquals(night.copy(enabled = false), result.pending)
        assertEquals(at(2026, 10, 11, 7), result.pendingFromWall)
        assertTrue(result.effectiveAt(now).enabled)
        assertFalse(result.effectiveAt(at(2026, 10, 11, 7)).enabled)
    }

    @Test
    fun tighteningAtNightAppliesImmediately() {
        val stricter = night.copy(startMinute = 22 * 60 + 30, wakeMinute = 8 * 60)
        val loose = night.copy(nightAllowanceMinutes = 10)
        val now = at(2026, 10, 11, 1)
        val result = BedtimePolicy.applyChange(BedtimeConfig(loose, null, 0L), stricter, now, zone)
        assertEquals(stricter, result.active)
        assertNull(result.pending)
    }

    @Test
    fun mixedChangeAtNightKeepsStrictestPartsAndDefersTheRest() {
        // Отбой раньше (строже), но лимит больше (мягче).
        val requested = night.copy(startMinute = 23 * 60, nightAllowanceMinutes = 15)
        val result = BedtimePolicy.applyChange(BedtimeConfig(night, null, 0L), requested, at(2026, 10, 11, 1), zone)
        assertEquals(night.copy(startMinute = 23 * 60, nightAllowanceMinutes = 0), result.active)
        assertEquals(requested, result.pending)
    }

    @Test
    fun startTimesCompareAcrossMidnight() {
        val a = night.copy(startMinute = 23 * 60)
        val b = night.copy(startMinute = 30)
        assertEquals(23 * 60, BedtimePolicy.strictest(a, b).startMinute)
        assertTrue(BedtimePolicy.fromNoon(23 * 60) < BedtimePolicy.fromNoon(30))
        assertTrue(BedtimePolicy.isRelaxation(a, b))
        assertFalse(BedtimePolicy.isRelaxation(b, a))
    }
}
