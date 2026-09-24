package com.detoxin.app.ui.analytics.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.detoxin.app.ui.analytics.HeatmapData
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Календарная сетка стриков в стиле GitHub: 7 строк (Пн–Вс) × N недель.
 *
 * Размер ячейки вычисляется из фактических `size.width`/`size.height`, поэтому сетка
 * корректно масштабируется от 1 до 6 недель. Прямоугольники и подписи дней считаются в
 * `drawWithCache`; в кадре анимации меняется только альфа-канал: ячейки проявляются
 * «волной» слева направо и сверху вниз.
 *
 * Градации: 0% → пустая ячейка, 1–33% → светлая, 34–66% → средняя, 67–100% → насыщенная.
 * Дни вне интервала рисуются контуром, дни без привычек — приглушённой заливкой.
 */
@Composable
fun HabitHeatmap(
    data: HeatmapData,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    emptyColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    outlineColor: Color = MaterialTheme.colorScheme.outlineVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val appear = remember { Animatable(0f) }

    LaunchedEffect(data) {
        appear.snapTo(0f)
        appear.animateTo(
            1f,
            tween(durationMillis = BASE_APPEAR_MS + data.weeks.size * PER_WEEK_APPEAR_MS, easing = LinearEasing),
        )
    }

    val levelColors = remember(activeColor, emptyColor) {
        listOf(
            emptyColor,
            activeColor.copy(alpha = 0.35f),
            activeColor.copy(alpha = 0.65f),
            activeColor,
        )
    }

    Canvas(
        modifier = modifier.drawWithCache {
            val weeks = data.weeks
            val weekCount = weeks.size.coerceAtLeast(1)
            val locale = Locale.getDefault()

            val dayLabels = LABELED_DAYS.map { day ->
                day to textMeasurer.measure(day.getDisplayName(TextStyle.SHORT, locale), labelStyle)
            }
            val labelColumnWidth = (dayLabels.maxOfOrNull { it.second.size.width } ?: 0) + 6.dp.toPx()
            val gap = 4.dp.toPx()
            val maxCell = 32.dp.toPx()
            val cellByHeight = (size.height - gap * (DAYS_PER_WEEK - 1)) / DAYS_PER_WEEK
            val cellByWidth = (size.width - labelColumnWidth - gap * (weekCount - 1)) / weekCount
            val cell = min(maxCell, min(cellByHeight, cellByWidth)).coerceAtLeast(1f)
            val corner = CornerRadius(min(4.dp.toPx(), cell / 4f))
            val outline = Stroke(width = 1.dp.toPx())
            val cellSize = Size(cell, cell)

            val origins = Array(weeks.size) { week ->
                Array(DAYS_PER_WEEK) { day ->
                    Offset(labelColumnWidth + week * (cell + gap), day * (cell + gap))
                }
            }
            val labelOffsets = dayLabels.map { (day, measured) ->
                val row = day.value - DayOfWeek.MONDAY.value
                measured to Offset(0f, row * (cell + gap) + (cell - measured.size.height) / 2f)
            }
            val waveLength = weeks.size + DAYS_PER_WEEK * ROW_DELAY

            onDrawBehind {
                for ((measured, offset) in labelOffsets) {
                    drawText(measured, topLeft = offset)
                }
                val front = appear.value * (waveLength + 1f)
                for (week in weeks.indices) {
                    val column = weeks[week]
                    for (day in column.indices) {
                        val alpha = (front - week - day * ROW_DELAY).coerceIn(0f, 1f)
                        if (alpha <= 0f) continue
                        val cellData = column[day]
                        val origin = origins[week][day]
                        if (!cellData.inRange) {
                            drawRoundRect(outlineColor, origin, cellSize, corner, style = outline, alpha = alpha)
                            continue
                        }
                        val ratio = cellData.completionRatio
                        if (ratio == null) {
                            drawRoundRect(emptyColor, origin, cellSize, corner, alpha = alpha * NO_HABITS_ALPHA)
                        } else {
                            drawRoundRect(levelColors[levelOf(ratio)], origin, cellSize, corner, alpha = alpha)
                        }
                    }
                }
            }
        }
    ) {}
}

/** 0% → 0, 1–33% → 1, 34–66% → 2, 67–100% → 3. */
internal fun levelOf(ratio: Float): Int {
    val percent = (ratio * 100f).roundToInt()
    return when {
        percent <= 0 -> 0
        percent <= 33 -> 1
        percent <= 66 -> 2
        else -> 3
    }
}

private val LABELED_DAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
private const val DAYS_PER_WEEK = 7
private const val ROW_DELAY = 0.15f
private const val NO_HABITS_ALPHA = 0.4f
private const val BASE_APPEAR_MS = 450
private const val PER_WEEK_APPEAR_MS = 60
