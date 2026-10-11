package com.detoxin.app.usage

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Правила режима «Отбой» — чистые функции без Android-зависимостей.
 *
 * Окна сна вычисляются через `ZonedDateTime`, поэтому переход на летнее/зимнее время
 * сдвигает окно корректно, а не на час.
 */
object BedtimePolicy {

    /** Напоминание и «замок» ослабления начинаются за столько минут до отбоя. */
    const val REMINDER_LEAD_MINUTES = 30L

    /** Допуск автопривычки «Лёг вовремя»: секунды под экраном блокировки не портят ночь. */
    const val AUTO_HABIT_GRACE_MS = 2L * 60L * 1_000L

    private const val MS_PER_MINUTE = 60_000L

    /** Окно, начинающееся вечером [date]. Если подъём не позже отбоя — заканчивается на следующий день. */
    fun windowStartingOn(date: LocalDate, zone: ZoneId, settings: BedtimeSettings): NightWindow {
        val start = date.atTime(timeOf(settings.startMinute)).atZone(zone)
        val endDate = if (settings.wakeMinute > settings.startMinute) date else date.plusDays(1)
        val end = endDate.atTime(timeOf(settings.wakeMinute)).atZone(zone)
        return NightWindow(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli(), date)
    }

    /** Окно, внутри которого [nowWall]; `null` — режим выключен или сейчас не ночь. */
    fun currentWindow(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): NightWindow? {
        if (!settings.enabled || !settings.hasWindow) return null
        val today = dateOf(nowWall, zone)
        for (date in arrayOf(today, today.minusDays(1))) {
            val window = windowStartingOn(date, zone, settings)
            if (nowWall in window) return window
        }
        return null
    }

    fun isInBedtime(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): Boolean =
        currentWindow(nowWall, zone, settings) != null

    /** Самое позднее окно, которое уже началось (текущее или прошлой ночи), независимо от `enabled`. */
    fun latestStartedWindow(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): NightWindow {
        val today = dateOf(nowWall, zone)
        val tonight = windowStartingOn(today, zone, settings)
        return if (tonight.startWall <= nowWall) tonight else windowStartingOn(today.minusDays(1), zone, settings)
    }

    /** Начало текущего окна (если сейчас ночь) или последнего начавшегося. */
    fun bedtimeWindowStart(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): Long =
        (currentWindow(nowWall, zone, settings) ?: latestStartedWindow(nowWall, zone, settings)).startWall

    /** Ближайшее окно, которое начнётся строго после [nowWall]. */
    fun nextWindow(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): NightWindow {
        var date = dateOf(nowWall, zone).minusDays(1)
        while (true) {
            val window = windowStartingOn(date, zone, settings)
            if (window.startWall > nowWall) return window
            date = date.plusDays(1)
        }
    }

    /** Момент ближайшего напоминания «через 30 минут отбой», строго в будущем. */
    fun nextReminderAt(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): Long {
        val lead = REMINDER_LEAD_MINUTES * MS_PER_MINUTE
        var window = nextWindow(nowWall, zone, settings)
        while (window.startWall - lead <= nowWall) {
            window = nextWindow(window.startWall, zone, settings)
        }
        return window.startWall - lead
    }

    /** Ближайший подъём: конец текущего окна или следующего. */
    fun nextWakeAt(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): Long =
        (currentWindow(nowWall, zone, settings) ?: nextWindow(nowWall, zone, settings)).endWall

    /**
     * Начало «суток» для дневного лимита. Обычно полночь, но если сейчас ночь и окно началось
     * вчера, сутки продолжаются до подъёма: в 00:00 не появляется свежий час TikTok.
     */
    fun budgetDayStart(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): Long {
        val midnight = startOfDay(nowWall, zone)
        val window = currentWindow(nowWall, zone, settings) ?: return midnight
        return if (window.startWall < midnight) startOfDay(window.startWall, zone) else midnight
    }

    /** До какого момента держится дневной лимит: до полуночи или, если на неё приходится окно сна, до подъёма. */
    fun dailyBlockUntil(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): Long {
        val nextMidnight = dateOf(nowWall, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (!settings.enabled || !settings.hasWindow) return nextMidnight
        currentWindow(nowWall, zone, settings)?.let { return it.endWall }
        val tonight = windowStartingOn(dateOf(nowWall, zone), zone, settings)
        return if (nextMidnight in tonight) tonight.endWall else nextMidnight
    }

    /**
     * Ослаблять настройки нельзя ночью и за [REMINDER_LEAD_MINUTES] до отбоя —
     * именно тогда тянет «на минутку» выключить режим.
     */
    fun isLocked(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): Boolean = lockEnd(nowWall, zone, settings) != null

    /** Конец текущего «замка» (подъём) или `null`, если менять можно свободно. */
    fun lockEnd(nowWall: Long, zone: ZoneId, settings: BedtimeSettings): Long? {
        if (!settings.enabled || !settings.hasWindow) return null
        currentWindow(nowWall, zone, settings)?.let { return it.endWall }
        val next = nextWindow(nowWall, zone, settings)
        return if (next.startWall - REMINDER_LEAD_MINUTES * MS_PER_MINUTE <= nowWall) next.endWall else null
    }

    /**
     * Применение изменения настроек с защитой от ослабления в момент слабости.
     *
     * Вне замка новые настройки действуют сразу. В замке сразу применяется только ужесточение:
     * действующими становятся самые строгие значения из старых и новых ([strictest]), а
     * запрошенные — если они мягче — откладываются до конца текущего окна (утра).
     */
    fun applyChange(config: BedtimeConfig, requested: BedtimeSettings, nowWall: Long, zone: ZoneId): BedtimeConfig {
        val current = config.effectiveAt(nowWall)
        val lockEnd = lockEnd(nowWall, zone, current)
            ?: return BedtimeConfig(active = requested, pending = null, pendingFromWall = 0L)
        val strict = strictest(current, requested)
        return if (strict == requested) {
            BedtimeConfig(active = requested, pending = null, pendingFromWall = 0L)
        } else {
            BedtimeConfig(active = strict, pending = requested, pendingFromWall = lockEnd)
        }
    }

    /** Есть ли в [requested] хоть одно ослабление относительно [current]. */
    fun isRelaxation(current: BedtimeSettings, requested: BedtimeSettings): Boolean =
        strictest(current, requested) != requested

    /**
     * Покомпонентно строгие настройки: включено, если включено хоть где-то; отбой раньше,
     * подъём позже, ночной лимит меньше. Время сравнивается относительно полудня, чтобы
     * 23:00 было «раньше» 00:30.
     */
    fun strictest(a: BedtimeSettings, b: BedtimeSettings): BedtimeSettings {
        if (!a.enabled) return b
        if (!b.enabled) return a
        return BedtimeSettings(
            enabled = true,
            startMinute = if (fromNoon(a.startMinute) <= fromNoon(b.startMinute)) a.startMinute else b.startMinute,
            wakeMinute = if (fromNoon(a.wakeMinute) >= fromNoon(b.wakeMinute)) a.wakeMinute else b.wakeMinute,
            nightAllowanceMinutes = minOf(a.nightAllowanceMinutes, b.nightAllowanceMinutes),
        )
    }

    /** Минуты от полудня: ночные значения упорядочиваются естественно (22:00 < 23:30 < 01:00 < 07:00). */
    fun fromNoon(minuteOfDay: Int): Int =
        Math.floorMod(minuteOfDay - BedtimeSettings.MINUTES_PER_DAY / 2, BedtimeSettings.MINUTES_PER_DAY)

    fun dateOf(wall: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate()

    private fun startOfDay(wall: Long, zone: ZoneId): Long =
        dateOf(wall, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun timeOf(minuteOfDay: Int): LocalTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
}
