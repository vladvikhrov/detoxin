package com.detoxin.app.ui.common.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
data class TrendLineChartColors(
    val reference: Color,
    val guide: Color,
    val axis: Color,
    val grid: Color,
    val tooltipContainer: Color,
    val tooltipContent: Color,
    val label: Color,
    val markerHalo: Color,
)

object TrendLineChartDefaults {
    @Composable
    fun colors(): TrendLineChartColors = TrendLineChartColors(
        reference = MaterialTheme.colorScheme.error,
        guide = MaterialTheme.colorScheme.outline,
        axis = MaterialTheme.colorScheme.outlineVariant,
        grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = GRID_ALPHA),
        tooltipContainer = MaterialTheme.colorScheme.inverseSurface,
        tooltipContent = MaterialTheme.colorScheme.inverseOnSurface,
        label = MaterialTheme.colorScheme.onSurfaceVariant,
        markerHalo = MaterialTheme.colorScheme.surface,
    )
}

/**
 * Линейная диаграмма динамики: одна или несколько линий, показывающих рост и падение.
 *
 * - Сглаживание — монотонный сплайн Фрича–Карлсона ([TrendMath.monotoneTangents]), переведённый
 *   в кубические Безье: кривая не уходит ниже нуля и не рисует ложных пиков между точками.
 * - `null` в [LineSeries.values] разрывает линию; одиночная точка между разрывами рисуется маркером.
 * - Градиентная заливка — только под первой серией: она главная, остальные читаются как детализация.
 * - Маркеры на значениях появляются, только если между точками хватает места; последняя
 *   точка каждой серии выделена крупным маркером — «где мы сейчас».
 *
 * Производительность: все Path, подписи и пунктир строятся в `drawWithCache` один раз на
 * размер и данные. Анимация появления — раскрытие слева направо через `clipRect`; её прогресс
 * читается только в фазе отрисовки, поэтому ни рекомпозиций, ни пересборки кэша она не вызывает.
 * Анимация запускается при смене оси X (другой диапазон), а не при каждом обновлении значений,
 * чтобы фоновая синхронизация статистики не «перерисовывала» график на глазах.
 * Курсор тултипа живёт в отдельном слое (тело Canvas) и не трогает кэш.
 *
 * @param tooltipFormatter подпись значения в тултипе (по умолчанию — как на оси).
 * @param yMax фиксированная верхняя граница (например, `1f` для долей); `null` — по данным.
 * @param minYMax нижняя граница автоматического масштаба, чтобы почти пустой график не «прыгал».
 * @param contentDescription текстовое резюме графика для TalkBack.
 */
@Composable
fun TrendLineChart(
    series: List<LineSeries>,
    xLabels: List<String>,
    yFormatter: (Float) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tooltipTitles: List<String> = xLabels,
    tooltipFormatter: (Float) -> String = yFormatter,
    yMax: Float? = null,
    minYMax: Float = 1f,
    referenceLine: ReferenceLine? = null,
    colors: TrendLineChartColors = TrendLineChartDefaults.colors(),
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.label)
    val referenceStyle = MaterialTheme.typography.labelSmall.copy(color = colors.reference)
    val tooltipStyle = MaterialTheme.typography.labelMedium.copy(color = colors.tooltipContent)
    val pointCount = xLabels.size

    val reveal = remember { Animatable(0f) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val maxY = remember(series, yMax, minYMax, referenceLine) {
        yMax ?: TrendMath.niceMax(
            value = maxOf(series.peak(), referenceLine?.value ?: 0f),
            minimum = minYMax,
        )
    }

    LaunchedEffect(xLabels) {
        selectedIndex = selectedIndex?.takeIf { it < pointCount }
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = REVEAL_DURATION_MS, easing = FastOutSlowInEasing))
    }

    Canvas(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(pointCount) {
                detectTapGestures(
                    onTap = { offset ->
                        val nearest = nearestIndex(offset.x, pointCount)
                        selectedIndex = if (nearest == selectedIndex) null else nearest
                    }
                )
            }
            .pointerInput(pointCount) {
                detectDragGestures(
                    onDragStart = { offset -> selectedIndex = nearestIndex(offset.x, pointCount) },
                    onDrag = { change, _ ->
                        change.consume()
                        selectedIndex = nearestIndex(change.position.x, pointCount)
                    },
                )
            }
            .drawWithCache {
                val layout = chartLayout(size)
                val xs = FloatArray(pointCount) { layout.xAt(it, pointCount) }
                val geometry = series.mapIndexed { index, line ->
                    SeriesGeometry.build(line, xs, layout, maxY, withFill = index == 0)
                }
                val spacing = if (pointCount > 1) (layout.right - layout.left) / (pointCount - 1) else Float.MAX_VALUE
                val showMarkers = spacing >= MARKER_MIN_SPACING.dp.toPx()

                val lineStroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val axisStroke = 1.dp.toPx()
                val markerRadius = 2.5.dp.toPx()
                val lastRadius = 4.5.dp.toPx()
                val haloRadius = 6.5.dp.toPx()
                val fillBrushes = geometry.map { item ->
                    Brush.verticalGradient(
                        colors = listOf(item.color.copy(alpha = FILL_TOP_ALPHA), item.color.copy(alpha = 0f)),
                        startY = layout.top,
                        endY = layout.bottom,
                    )
                }

                val midY = layout.yAt(maxY / 2f, maxY)
                val topLabel = textMeasurer.measure(yFormatter(maxY), labelStyle)
                val midLabel = textMeasurer.measure(yFormatter(maxY / 2f), labelStyle)
                val labelGap = 2.dp.toPx()

                val reference = referenceLine?.takeIf { it.value in 0f..maxY }?.let { line ->
                    val y = layout.yAt(line.value, maxY)
                    val text = textMeasurer.measure(line.label, referenceStyle)
                    Triple(
                        y,
                        text,
                        Offset(
                            x = (layout.right - text.size.width).coerceAtLeast(0f),
                            y = (y - text.size.height - labelGap).coerceAtLeast(0f),
                        ),
                    )
                }
                val referenceEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)

                val maxLabels = max(1, (size.width / AXIS_LABEL_SLOT.dp.toPx()).toInt())
                val labelStep = max(1, ceil(pointCount / maxLabels.toFloat()).toInt())
                val axisLabels = (0 until pointCount step labelStep).map { index ->
                    val measured = textMeasurer.measure(xLabels[index], labelStyle)
                    val x = (xs[index] - measured.size.width / 2f)
                        .coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f))
                    measured to Offset(x, layout.bottom + 4.dp.toPx())
                }

                onDrawBehind {
                    drawLine(colors.grid, Offset(layout.left, layout.top), Offset(layout.right, layout.top), axisStroke / 2f)
                    drawLine(colors.grid, Offset(layout.left, midY), Offset(layout.right, midY), axisStroke / 2f)
                    drawLine(colors.axis, Offset(0f, layout.bottom), Offset(size.width, layout.bottom), axisStroke)
                    drawText(topLabel, topLeft = Offset(0f, layout.top - topLabel.size.height - labelGap))
                    drawText(midLabel, topLeft = Offset(0f, midY - midLabel.size.height - labelGap))

                    if (reference != null) {
                        val (y, text, textOffset) = reference
                        drawLine(
                            color = colors.reference,
                            start = Offset(layout.left, y),
                            end = Offset(layout.right, y),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = referenceEffect,
                        )
                        drawText(text, topLeft = textOffset)
                    }

                    val revealRight = layout.left + (layout.right - layout.left) * reveal.value + haloRadius
                    clipRect(right = revealRight) {
                        geometry.forEachIndexed { index, item ->
                            for (fill in item.fills) drawPath(fill, fillBrushes[index])
                            for (path in item.lines) drawPath(path, item.color, style = lineStroke)
                            for (point in item.singles) drawCircle(item.color, lastRadius, point)
                            if (showMarkers) {
                                for (point in item.points) drawCircle(item.color, markerRadius, point)
                            }
                            item.last?.let { point ->
                                drawCircle(colors.markerHalo, haloRadius, point)
                                drawCircle(item.color, lastRadius, point)
                            }
                        }
                    }

                    for ((measured, offset) in axisLabels) drawText(measured, topLeft = offset)
                }
            }
    ) {
        val index = selectedIndex ?: return@Canvas
        if (index !in 0 until pointCount) return@Canvas
        drawCursorAndTooltip(
            index = index,
            series = series,
            title = tooltipTitles.getOrElse(index) { xLabels[index] },
            maxY = maxY,
            valueFormatter = tooltipFormatter,
            textMeasurer = textMeasurer,
            style = tooltipStyle,
            colors = colors,
            pointCount = pointCount,
        )
    }
}

/** Готовая геометрия одной серии: Path строятся один раз на размер и данные. */
private class SeriesGeometry(
    val color: Color,
    val lines: List<Path>,
    val fills: List<Path>,
    val singles: List<Offset>,
    val points: List<Offset>,
    val last: Offset?,
) {
    companion object {
        fun build(series: LineSeries, xs: FloatArray, layout: ChartLayout, maxY: Float, withFill: Boolean): SeriesGeometry {
            val lines = ArrayList<Path>()
            val fills = ArrayList<Path>()
            val singles = ArrayList<Offset>()
            val points = ArrayList<Offset>()
            var last: Offset? = null

            // Непрерывные отрезки без null: каждый — отдельный сплайн.
            var runStart = -1
            val count = minOf(xs.size, series.values.size)
            for (index in 0..count) {
                val value = if (index < count) series.values[index] else null
                if (value != null) {
                    val point = Offset(xs[index], layout.yAt(value, maxY))
                    points += point
                    last = point
                    if (runStart < 0) runStart = index
                    continue
                }
                if (runStart < 0) continue
                val runEnd = index - 1
                if (runEnd == runStart) {
                    singles += Offset(xs[runStart], layout.yAt(series.values[runStart] ?: 0f, maxY))
                } else {
                    val runXs = FloatArray(runEnd - runStart + 1) { xs[runStart + it] }
                    val runYs = FloatArray(runXs.size) { layout.yAt(series.values[runStart + it] ?: 0f, maxY) }
                    val line = monotonePath(runXs, runYs)
                    lines += line
                    if (withFill) {
                        fills += Path().apply {
                            addPath(line)
                            lineTo(runXs.last(), layout.bottom)
                            lineTo(runXs.first(), layout.bottom)
                            close()
                        }
                    }
                }
                runStart = -1
            }
            return SeriesGeometry(series.color, lines, fills, singles, points, last)
        }

        /** Участки сплайна Эрмита как кубические Безье с контрольными точками на трети шага по X. */
        private fun monotonePath(xs: FloatArray, ys: FloatArray): Path {
            val path = Path()
            path.moveTo(xs[0], ys[0])
            val tangents = TrendMath.monotoneTangents(xs, ys)
            for (i in 0 until xs.size - 1) {
                val dx = (xs[i + 1] - xs[i]) / 3f
                path.cubicTo(
                    xs[i] + dx, ys[i] + tangents[i] * dx,
                    xs[i + 1] - dx, ys[i + 1] - tangents[i + 1] * dx,
                    xs[i + 1], ys[i + 1],
                )
            }
            return path
        }
    }
}

private fun DrawScope.drawCursorAndTooltip(
    index: Int,
    series: List<LineSeries>,
    title: String,
    maxY: Float,
    valueFormatter: (Float) -> String,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    colors: TrendLineChartColors,
    pointCount: Int,
) {
    val layout = chartLayout(size)
    val x = layout.xAt(index, pointCount)
    drawLine(
        color = colors.guide,
        start = Offset(x, layout.top),
        end = Offset(x, layout.bottom),
        strokeWidth = 1.dp.toPx(),
    )

    var anchorY = layout.bottom
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
        var hasValues = false
        for (line in series) {
            val value = line.values.getOrNull(index) ?: continue
            hasValues = true
            val y = layout.yAt(value, maxY)
            anchorY = minOf(anchorY, y)
            drawCircle(colors.markerHalo, radius = 6.dp.toPx(), center = Offset(x, y))
            drawCircle(line.color, radius = 4.dp.toPx(), center = Offset(x, y))
            append("\n")
            withStyle(SpanStyle(color = line.color)) { append("● ") }
            append(if (series.size > 1) "${line.label}: ${valueFormatter(value)}" else valueFormatter(value))
        }
        if (!hasValues) append("\nнет данных")
    }

    val measured = textMeasurer.measure(text, style)
    val paddingH = 10.dp.toPx()
    val paddingV = 6.dp.toPx()
    val gap = 10.dp.toPx()
    val boxWidth = measured.size.width + paddingH * 2f
    val boxHeight = measured.size.height + paddingV * 2f
    val boxLeft = (x - boxWidth / 2f).coerceIn(0f, (size.width - boxWidth).coerceAtLeast(0f))
    val aboveTop = anchorY - gap - boxHeight
    val boxTop = if (aboveTop >= 0f) aboveTop else (anchorY + gap).coerceAtMost((size.height - boxHeight).coerceAtLeast(0f))

    drawRoundRect(
        color = colors.tooltipContainer,
        topLeft = Offset(boxLeft, boxTop),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(8.dp.toPx()),
    )
    drawText(measured, topLeft = Offset(boxLeft + paddingH, boxTop + paddingV))
}

/** Геометрия области построения, общая для отрисовки и обработки жестов. */
private class ChartLayout(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun xAt(index: Int, count: Int): Float =
        if (count <= 1) (left + right) / 2f else left + (right - left) * index / (count - 1)

    fun yAt(value: Float, maxY: Float): Float =
        bottom - (value / maxY).coerceIn(0f, 1f) * (bottom - top)
}

private fun Density.chartLayout(size: Size): ChartLayout {
    val horizontalInset = 8.dp.toPx()
    return ChartLayout(
        left = horizontalInset,
        top = 28.dp.toPx(),
        right = size.width - horizontalInset,
        bottom = size.height - 20.dp.toPx(),
    )
}

private fun PointerInputScope.nearestIndex(x: Float, count: Int): Int? {
    if (count == 0) return null
    if (count == 1) return 0
    val layout = chartLayout(Size(size.width.toFloat(), size.height.toFloat()))
    val fraction = ((x - layout.left) / (layout.right - layout.left)).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt()
}

private fun List<LineSeries>.peak(): Float {
    var peak = 0f
    for (line in this) {
        for (value in line.values) {
            if (value != null && value > peak) peak = value
        }
    }
    return peak
}

private const val REVEAL_DURATION_MS = 700
private const val FILL_TOP_ALPHA = 0.30f
private const val GRID_ALPHA = 0.5f
private const val MARKER_MIN_SPACING = 14
private const val AXIS_LABEL_SLOT = 32
