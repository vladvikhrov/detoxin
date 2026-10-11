package com.detoxin.app.usage

import java.time.LocalDate

/**
 * Настройки режима «Отбой». Время хранится как минута суток (`0..1439`), окно может переходить
 * через полночь (23:30 → 07:00).
 *
 * @property nightAllowanceMinutes сколько минут отслеживаемых приложений суммарно разрешено за
 * ночь; `0` — блокировка сразу при открытии.
 */
data class BedtimeSettings(
    val enabled: Boolean,
    val startMinute: Int,
    val wakeMinute: Int,
    val nightAllowanceMinutes: Int,
) {
    val allowanceMs: Long get() = nightAllowanceMinutes * MS_PER_MINUTE

    /** Окно нулевой длины не блокирует ничего — UI не даёт сохранить такие настройки. */
    val hasWindow: Boolean get() = startMinute != wakeMinute

    companion object {
        const val MAX_ALLOWANCE_MINUTES = 15
        const val MINUTES_PER_DAY = 24 * 60
        private const val MS_PER_MINUTE = 60_000L

        val DEFAULT = BedtimeSettings(
            enabled = false,
            startMinute = 23 * 60 + 30,
            wakeMinute = 7 * 60,
            nightAllowanceMinutes = 0,
        )
    }
}

/**
 * Сохранённая конфигурация с отложенным ослаблением.
 *
 * Ослабление, сделанное ночью, не применяется сразу: [pending] вступает в силу в
 * [pendingFromWall] (утром, в конце текущего окна сна). До этого действует [active] —
 * самое строгое сочетание старых и новых настроек.
 */
data class BedtimeConfig(
    val active: BedtimeSettings,
    val pending: BedtimeSettings?,
    val pendingFromWall: Long,
) {
    fun effectiveAt(nowWall: Long): BedtimeSettings =
        if (pending != null && nowWall >= pendingFromWall) pending else active

    /** Отложенное изменение, которое ещё не вступило в силу. */
    fun pendingAt(nowWall: Long): BedtimeSettings? = pending?.takeIf { nowWall < pendingFromWall }

    companion object {
        val DEFAULT = BedtimeConfig(active = BedtimeSettings.DEFAULT, pending = null, pendingFromWall = 0L)
    }
}

/** Одно окно сна `[startWall, endWall)`; [startDate] — дата вечера, с которого окно начинается. */
data class NightWindow(
    val startWall: Long,
    val endWall: Long,
    val startDate: LocalDate,
) {
    operator fun contains(wall: Long): Boolean = wall in startWall until endWall

    /** Длина пересечения с отрезком `[fromWall, toWall)`. */
    fun overlapMs(fromWall: Long, toWall: Long): Long =
        (minOf(toWall, endWall) - maxOf(fromWall, startWall)).coerceAtLeast(0L)
}
