package com.detoxin.app.ui.habits.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.detoxin.app.ui.habits.WeekdayStat
import kotlin.math.roundToInt

/**
 * Частота выполнения по дням недели (Пн–Вс): горизонтальная полоса на каждый день,
 * подпись дня слева и процент справа.
 *
 * Подсветка: «сильные» дни (≥ 67%) — цвет привычки, «просадочные» (≤ 33%) — цвет
 * предупреждения, промежуточные — приглушённый цвет привычки. Процент окрашен так же и
 * выделен жирным для сильных дней. Полосы плавно вырастают при смене диапазона.
 */
@Composable
fun HabitWeekdayFrequencyChart(
    stats: List<WeekdayStat>,
    strongColor: Color,
    modifier: Modifier = Modifier,
    weakColor: Color = MaterialTheme.colorScheme.error,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    mutedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val textMeasurer = rememberTextMeasurer()
    val baseStyle = MaterialTheme.typography.labelMedium
    val growth = remember { Animatable(0f) }

    LaunchedEffect(stats) {
        growth.snapTo(0f)
        growth.animateTo(1f, tween(durationMillis = GROW_DURATION_MS, easing = FastOutSlowInEasing))
    }

    Canvas(
        modifier = modifier.drawWithCache {
            val rowCount = stats.size.coerceAtLeast(1)
            val rowHeight = size.height / rowCount
            val barHeight = (rowHeight * BAR_HEIGHT_RATIO).coerceAtMost(14.dp.toPx())
            val radius = CornerRadius(barHeight / 2f)
            val gap = 8.dp.toPx()

            val dayLabels = stats.map { textMeasurer.measure(it.label, baseStyle.copy(color = labelColor)) }
            val percentLabels = stats.map { stat ->
                val tone = toneOf(stat.ratio)
                val color = when (tone) {
                    Tone.STRONG -> strongColor
                    Tone.WEAK -> weakColor
                    Tone.MIDDLE -> labelColor
                    Tone.NONE -> mutedColor
                }
                val text = stat.ratio?.let { "${(it * 100f).roundToInt()}%" } ?: "—"
                val weight = if (tone == Tone.STRONG) FontWeight.Bold else FontWeight.Medium
                textMeasurer.measure(text, baseStyle.copy(color = color, fontWeight = weight))
            }
            val labelColumn = (dayLabels.maxOfOrNull { it.size.width } ?: 0) + gap
            val percentColumn = (textMeasurer.measure("100%", baseStyle).size.width) + gap
            val barLeft = labelColumn
            val barWidth = (size.width - labelColumn - percentColumn).coerceAtLeast(1f)

            val fills = stats.map { stat ->
                when (toneOf(stat.ratio)) {
                    Tone.STRONG -> strongColor
                    Tone.WEAK -> weakColor.copy(alpha = 0.85f)
                    Tone.MIDDLE -> strongColor.copy(alpha = 0.55f)
                    Tone.NONE -> trackColor
                }
            }
            val rowCenters = FloatArray(stats.size) { rowHeight * it + rowHeight / 2f }

            onDrawBehind {
                val progress = growth.value
                for (index in stats.indices) {
                    val centerY = rowCenters[index]
                    val barTop = centerY - barHeight / 2f
                    val day = dayLabels[index]
                    drawText(day, topLeft = Offset(0f, centerY - day.size.height / 2f))

                    drawRoundRect(trackColor, Offset(barLeft, barTop), Size(barWidth, barHeight), radius)
                    val ratio = stats[index].ratio ?: 0f
                    val fillWidth = barWidth * ratio.coerceIn(0f, 1f) * progress
                    if (fillWidth > 0f) {
                        drawRoundRect(fills[index], Offset(barLeft, barTop), Size(fillWidth, barHeight), radius)
                    }

                    val percent = percentLabels[index]
                    drawText(
                        percent,
                        topLeft = Offset(size.width - percent.size.width, centerY - percent.size.height / 2f),
                    )
                }
            }
        }
    ) {}
}

private enum class Tone { STRONG, MIDDLE, WEAK, NONE }

private fun toneOf(ratio: Float?): Tone = when {
    ratio == null -> Tone.NONE
    ratio >= STRONG_THRESHOLD -> Tone.STRONG
    ratio <= WEAK_THRESHOLD -> Tone.WEAK
    else -> Tone.MIDDLE
}

private const val STRONG_THRESHOLD = 0.67f
private const val WEAK_THRESHOLD = 0.33f
private const val BAR_HEIGHT_RATIO = 0.45f
private const val GROW_DURATION_MS = 600
