package com.detoxin.app.ui.analytics.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.detoxin.app.ui.analytics.UsageChartData
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Палитра сегментов: [calm] — лимит соблюдён, [warning] — перерасход.
 * Индексы: 0 — TikTok, 1 — YouTube, 2 — остальные приложения.
 */
@Immutable
data class StackedBarColors(
    val calm: List<Color>,
    val warning: List<Color>,
    val limitLine: Color,
    val axis: Color,
    val label: Color,
)

object StackedBarDefaults {
    val CalmPalette = listOf(Color(0xFF1B7F4B), Color(0xFF3FAE6F), Color(0xFF9AD8AE))
    val WarningPalette = listOf(Color(0xFFC62828), Color(0xFFEF6C00), Color(0xFFFFB74D))

    @Composable
    fun colors(): StackedBarColors = StackedBarColors(
        calm = CalmPalette,
        warning = WarningPalette,
        limitLine = MaterialTheme.colorScheme.error,
        axis = MaterialTheme.colorScheme.outlineVariant,
        label = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Столбчатая диаграмма экранного времени с разбивкой TikTok / YouTube / остальные.
 *
 * Производительность: вся геометрия (масштаб, позиции столбцов, пунктир лимита, измеренные
 * подписи) считается в `drawWithCache` один раз на размер и данные. Прогресс роста
 * [Animatable] читается только в фазе отрисовки (`onDrawBehind`), поэтому анимация не
 * вызывает ни рекомпозиций, ни пересборки кэша; единственный Path скругления переиспользуется.
 *
 * Цвет: доля от лимита вычисляется по анимированной высоте, поэтому столбец плавно
 * «перетекает» из зелёного в оранжево-красный спектр в момент пересечения линии лимита.
 */
@Composable
fun StackedBarChart(
    data: UsageChartData,
    modifier: Modifier = Modifier,
    colors: StackedBarColors = StackedBarDefaults.colors(),
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.label)
    val limitLabelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.limitLine)
    val growth = remember { Animatable(0f) }
    val roundedTopPath = remember { Path() }

    LaunchedEffect(data) {
        growth.snapTo(0f)
        growth.animateTo(1f, tween(durationMillis = GROW_DURATION_MS, easing = FastOutSlowInEasing))
    }

    Canvas(
        modifier = modifier.drawWithCache {
            val bars = data.bars
            val barCount = max(bars.size, 1)

            val bottomLabelArea = 20.dp.toPx()
            val topPadding = 16.dp.toPx()
            val chartLeft = 0f
            val chartRight = size.width
            val chartTop = topPadding
            val chartBottom = size.height - bottomLabelArea
            val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)

            val maxTotal = bars.maxOfOrNull { it.totalMs } ?: 0L
            val maxValue = max(maxTotal, data.limitMs).toFloat() * HEADROOM
            val limit = data.limitMs.toFloat()

            val slot = (chartRight - chartLeft) / barCount
            val barWidth = slot * BAR_FILL_RATIO
            val cornerRadius = min(6.dp.toPx(), barWidth / 2f)
            val barLefts = FloatArray(bars.size) { chartLeft + slot * it + (slot - barWidth) / 2f }
            // Высоты сегментов при 100% прогресса: [bar][segment].
            val segmentHeights = Array(bars.size) { index ->
                val bar = bars[index]
                floatArrayOf(bar.tikTokMs.toFloat(), bar.youTubeMs.toFloat(), bar.otherMs.toFloat())
                    .map { it / maxValue * chartHeight }
                    .toFloatArray()
            }

            val limitY = chartBottom - limit / maxValue * chartHeight
            val limitPath = Path().apply {
                moveTo(chartLeft, limitY)
                lineTo(chartRight, limitY)
            }
            val limitStroke = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f),
            )
            val limitText = textMeasurer.measure(formatMinutesShort(data.limitMs), limitLabelStyle)
            val limitTextOffset = Offset(
                x = chartRight - limitText.size.width,
                y = (limitY - limitText.size.height - 2.dp.toPx()).coerceAtLeast(0f),
            )

            val maxLabels = max(1, (size.width / 28.dp.toPx()).toInt())
            val labelStep = max(1, ceil(bars.size / maxLabels.toFloat()).toInt())
            val axisLabels = ArrayList<Pair<TextLayoutResult, Offset>>()
            for (index in bars.indices step labelStep) {
                val layout = textMeasurer.measure(bars[index].label, labelStyle)
                val centerX = chartLeft + slot * index + slot / 2f
                val x = (centerX - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                axisLabels += layout to Offset(x, chartBottom + 4.dp.toPx())
            }
            val axisStroke = 1.dp.toPx()

            onDrawBehind {
                val progress = growth.value

                drawLine(
                    color = colors.axis,
                    start = Offset(chartLeft, chartBottom),
                    end = Offset(chartRight, chartBottom),
                    strokeWidth = axisStroke,
                )

                for (index in bars.indices) {
                    val heights = segmentHeights[index]
                    val animatedTotalMs = bars[index].totalMs * progress
                    val overLimit = if (limit > 0f) animatedTotalMs / limit else 0f
                    val warningFraction = ((overLimit - WARNING_START) / WARNING_SPAN).coerceIn(0f, 1f)
                    val topSegment = heights.indexOfLast { it > 0f }
                    val left = barLefts[index]
                    var cursorY = chartBottom

                    for (segment in heights.indices) {
                        val height = heights[segment] * progress
                        if (height <= 0f) continue
                        val top = cursorY - height
                        val color = lerp(colors.calm[segment], colors.warning[segment], warningFraction)
                        if (segment == topSegment) {
                            val radius = CornerRadius(min(cornerRadius, height))
                            roundedTopPath.reset()
                            roundedTopPath.addRoundRect(
                                RoundRect(
                                    left = left,
                                    top = top,
                                    right = left + barWidth,
                                    bottom = cursorY,
                                    topLeftCornerRadius = radius,
                                    topRightCornerRadius = radius,
                                    bottomRightCornerRadius = CornerRadius.Zero,
                                    bottomLeftCornerRadius = CornerRadius.Zero,
                                )
                            )
                            drawPath(roundedTopPath, color)
                        } else {
                            drawRect(color, topLeft = Offset(left, top), size = Size(barWidth, height))
                        }
                        cursorY = top
                    }
                }

                drawPath(limitPath, colors.limitLine, style = limitStroke)
                drawText(limitText, topLeft = limitTextOffset)
                for ((layout, offset) in axisLabels) {
                    drawText(layout, topLeft = offset)
                }
            }
        }
    ) {}
}

/** «15 мин», «1 ч», «1 ч 30 мин» — подпись линии лимита. */
internal fun formatMinutesShort(ms: Long): String {
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours == 0L -> "$minutes мин"
        minutes == 0L -> "$hours ч"
        else -> "$hours ч $minutes мин"
    }
}

private const val GROW_DURATION_MS = 700
private const val BAR_FILL_RATIO = 0.62f
private const val HEADROOM = 1.15f

/** Цвет начинает смещаться при 90% лимита и полностью «краснеет» при 140%. */
private const val WARNING_START = 0.9f
private const val WARNING_SPAN = 0.5f
