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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.detoxin.app.ui.habits.TrendBar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Динамика выполнения привычки: 7 (неделя), до 31 (месяц) или 12 (год) столбцов.
 *
 * Геометрия дорожек, пунктир 100%, подписи и градиент в цвет привычки считаются в
 * `drawWithCache`. Прогресс роста [Animatable] читается только в `onDrawBehind`, поэтому
 * анимация не пересобирает кэш; Path скругления верхушки один и переиспользуется.
 * Градиент задан на всю высоту графика: более высокий столбец насыщеннее у вершины.
 */
@Composable
fun HabitTrendBarChart(
    bars: List<TrendBar>,
    barColor: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    guideColor: Color = MaterialTheme.colorScheme.outlineVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val growth = remember { Animatable(0f) }
    val barPath = remember { Path() }

    LaunchedEffect(bars) {
        growth.snapTo(0f)
        growth.animateTo(1f, tween(durationMillis = GROW_DURATION_MS, easing = FastOutSlowInEasing))
    }

    Canvas(
        modifier = modifier.drawWithCache {
            val count = max(bars.size, 1)
            val topLabel = textMeasurer.measure("100%", labelStyle)
            val chartTop = topLabel.size.height + 4.dp.toPx()
            val chartBottom = size.height - 20.dp.toPx()
            val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)

            val slot = size.width / count
            val barWidth = slot * BAR_FILL_RATIO
            val radius = min(6.dp.toPx(), barWidth / 2f)
            val lefts = FloatArray(bars.size) { slot * it + (slot - barWidth) / 2f }

            // Дорожки (фон столбцов) статичны — строятся один раз на размер.
            val trackPath = Path().apply {
                for (index in bars.indices) {
                    addRoundRect(
                        RoundRect(
                            left = lefts[index],
                            top = chartTop,
                            right = lefts[index] + barWidth,
                            bottom = chartBottom,
                            cornerRadius = CornerRadius(radius),
                        )
                    )
                }
            }
            val gradient = Brush.verticalGradient(
                colors = listOf(barColor, barColor.copy(alpha = GRADIENT_BOTTOM_ALPHA)),
                startY = chartTop,
                endY = chartBottom,
            )
            val guidePath = Path().apply {
                moveTo(0f, chartTop)
                lineTo(size.width, chartTop)
            }
            val guideStroke = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )

            val maxLabels = max(1, (size.width / 24.dp.toPx()).toInt())
            val step = max(1, ceil(bars.size / maxLabels.toFloat()).toInt())
            val labels = ArrayList<Pair<TextLayoutResult, Offset>>()
            for (index in bars.indices step step) {
                val measured = textMeasurer.measure(bars[index].label, labelStyle)
                val x = (lefts[index] + barWidth / 2f - measured.size.width / 2f)
                    .coerceIn(0f, size.width - measured.size.width)
                labels += measured to Offset(x, chartBottom + 4.dp.toPx())
            }
            val topLabelOffset = Offset(size.width - topLabel.size.width, 0f)

            onDrawBehind {
                drawPath(trackPath, trackColor)
                drawPath(guidePath, guideColor, style = guideStroke)
                drawText(topLabel, topLeft = topLabelOffset)

                val progress = growth.value
                for (index in bars.indices) {
                    val bar = bars[index]
                    if (bar.isFuture || bar.value <= 0f) continue
                    val height = chartHeight * bar.value.coerceIn(0f, 1f) * progress
                    if (height <= 0f) continue
                    val cornerRadius = CornerRadius(min(radius, height / 2f))
                    barPath.reset()
                    barPath.addRoundRect(
                        RoundRect(
                            left = lefts[index],
                            top = chartBottom - height,
                            right = lefts[index] + barWidth,
                            bottom = chartBottom,
                            topLeftCornerRadius = cornerRadius,
                            topRightCornerRadius = cornerRadius,
                            bottomRightCornerRadius = CornerRadius(min(radius, height / 2f)),
                            bottomLeftCornerRadius = CornerRadius(min(radius, height / 2f)),
                        )
                    )
                    drawPath(barPath, gradient)
                }
                for ((measured, offset) in labels) {
                    drawText(measured, topLeft = offset)
                }
            }
        }
    ) {}
}

private const val GROW_DURATION_MS = 650
private const val BAR_FILL_RATIO = 0.6f
private const val GRADIENT_BOTTOM_ALPHA = 0.45f
