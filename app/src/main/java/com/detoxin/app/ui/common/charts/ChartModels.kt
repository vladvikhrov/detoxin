package com.detoxin.app.ui.common.charts

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Одна линия графика. [values] выровнены по оси X с подписями графика;
 * `null` — разрыв линии (будущий период, дни до появления привычки, нет данных).
 */
@Immutable
data class LineSeries(
    val label: String,
    val color: Color,
    val values: List<Float?>,
)

/** Горизонтальный пунктир (лимит, норма) с подписью у правого края. */
@Immutable
data class ReferenceLine(
    val value: Float,
    val label: String,
)

/** Как сравнивать половины периода в [TrendMath.percentChange]. */
enum class TrendMode {
    /** Отношение средних: «экранное время выросло на 12%». */
    RELATIVE,

    /** Разность средних долей `[0, 1]` в процентных пунктах: «выполнение +12%». */
    ABSOLUTE,
}
