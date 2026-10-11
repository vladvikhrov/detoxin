package com.detoxin.app.ui.analytics

import com.detoxin.app.util.formatDurationShort
import kotlin.math.roundToInt

/** Русское согласование: 1 блокировка, 2 блокировки, 5 блокировок. */
internal fun attemptsWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "блокировок"
        mod10 == 1 -> "блокировка"
        mod10 in 2..4 -> "блокировки"
        else -> "блокировок"
    }
}

/** Подпись оси для счётчиков: целые без дробной части, половинки — с одной цифрой. */
internal fun formatCount(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)

/** Значения графиков экранного времени хранятся в минутах. */
internal fun formatMinutesValue(minutes: Float): String = formatDurationShort((minutes * MS_PER_MINUTE).toLong())

internal fun msToMinutes(ms: Long): Float = ms / MS_PER_MINUTE.toFloat()

/** «Экранное время за неделю: снижение с 2 ч 10 мин до 1 ч 5 мин». */
internal fun describeLine(subject: String, values: List<Float?>, formatter: (Float) -> String): String {
    val known = values.filterNotNull()
    if (known.isEmpty()) return "$subject: пока нет данных."
    val first = known.first()
    val last = known.last()
    val direction = when {
        last.roundToInt() > first.roundToInt() -> "рост"
        last.roundToInt() < first.roundToInt() -> "снижение"
        else -> return "$subject: без изменений, ${formatter(last)}."
    }
    return "$subject: $direction с ${formatter(first)} до ${formatter(last)}."
}

private const val MS_PER_MINUTE = 60_000L
