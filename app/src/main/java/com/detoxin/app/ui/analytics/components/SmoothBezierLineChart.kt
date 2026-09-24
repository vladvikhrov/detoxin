package com.detoxin.app.ui.analytics.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.detoxin.app.ui.analytics.BlockPoint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Кривая спасённых открытий (Shorts, Клипы, Лента).
 *
 * Сглаживание — монотонный кубический сплайн Эрмита (Фрич–Карлсон), переведённый в
 * сегменты Безье (`cubicTo`). Монотонность гарантирует, что кривая не «проваливается»
 * ниже нуля и не рисует ложных пиков между целочисленными точками.
 *
 * Морфинг: при смене данных текущие отображаемые значения пересэмплируются на новое число
 * точек (24 часа ↔ 7 ↔ 30 дней), после чего единственный [Animatable] ведёт интерполяцию
 * старое → новое. Path кривой и заливки строятся в `drawWithCache`; кэш пересобирается
 * только во время морфинга, а перемещение курсора перерисовывает лишь слой тултипа.
 */
@Composable
fun SmoothBezierLineChart(
    points: List<BlockPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    guideColor: Color = MaterialTheme.colorScheme.outline,
    axisColor: Color = MaterialTheme.colorScheme.outlineVariant,
    tooltipContainerColor: Color = MaterialTheme.colorScheme.inverseSurface,
    tooltipContentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val tooltipStyle = MaterialTheme.typography.labelMedium.copy(color = tooltipContentColor)

    val morph = remember { Animatable(1f) }
    var fromValues by remember { mutableStateOf(FloatArray(0)) }
    var toValues by remember { mutableStateOf(FloatArray(0)) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val linePath = remember { Path() }
    val fillPath = remember { Path() }

    LaunchedEffect(points) {
        val targets = FloatArray(points.size) { points[it].count.toFloat() }
        val displayed = interpolate(fromValues, toValues, morph.value)
        fromValues = resample(displayed, targets.size)
        toValues = targets
        selectedIndex = selectedIndex?.takeIf { it < targets.size }
        morph.snapTo(0f)
        morph.animateTo(1f, tween(durationMillis = MORPH_DURATION_MS, easing = FastOutSlowInEasing))
    }

    Canvas(
        modifier = modifier
            .pointerInput(points.size) {
                detectTapGestures(
                    onTap = { offset ->
                        val nearest = nearestIndex(offset.x, points.size)
                        selectedIndex = if (nearest == selectedIndex) null else nearest
                    }
                )
            }
            .pointerInput(points.size) {
                detectDragGestures(
                    onDragStart = { offset ->
                        selectedIndex = nearestIndex(offset.x, points.size)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        selectedIndex = nearestIndex(change.position.x, points.size)
                    },
                )
            }
            .drawWithCache {
                val layout = chartLayout(size)
                val t = morph.value
                val values = interpolate(fromValues, toValues, t)
                val maxY = niceMax(lerpFloat(peak(fromValues), peak(toValues), t))
                val xs = FloatArray(values.size) { layout.xAt(it, values.size) }
                val ys = FloatArray(values.size) { layout.yAt(values[it], maxY) }

                buildMonotonePath(linePath, xs, ys)
                fillPath.reset()
                if (xs.size >= 2) {
                    fillPath.addPath(linePath)
                    fillPath.lineTo(xs.last(), layout.bottom)
                    fillPath.lineTo(xs.first(), layout.bottom)
                    fillPath.close()
                }
                val fillBrush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = FILL_TOP_ALPHA), lineColor.copy(alpha = 0f)),
                    startY = layout.top,
                    endY = layout.bottom,
                )
                val lineStroke = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )

                val maxLabels = max(1, (size.width / 32.dp.toPx()).toInt())
                val labelStep = max(1, ceil(points.size / maxLabels.toFloat()).toInt())
                val axisLabels = points.indices.step(labelStep).map { index ->
                    val measured = textMeasurer.measure(points[index].axisLabel, labelStyle)
                    val x = (layout.xAt(index, points.size) - measured.size.width / 2f)
                        .coerceIn(0f, size.width - measured.size.width)
                    measured to Offset(x, layout.bottom + 4.dp.toPx())
                }
                val maxLabel = textMeasurer.measure(maxY.roundToInt().toString(), labelStyle)
                val axisStroke = 1.dp.toPx()
                val singleDotRadius = 3.dp.toPx()

                onDrawBehind {
                    drawLine(axisColor, Offset(0f, layout.bottom), Offset(size.width, layout.bottom), axisStroke)
                    drawLine(axisColor, Offset(0f, layout.top), Offset(size.width, layout.top), axisStroke / 2f)
                    drawText(maxLabel, topLeft = Offset(0f, layout.top - maxLabel.size.height - 2.dp.toPx()))
                    if (xs.size >= 2) {
                        drawPath(fillPath, fillBrush)
                        drawPath(linePath, lineColor, style = lineStroke)
                    } else if (xs.size == 1) {
                        drawCircle(lineColor, singleDotRadius, Offset(xs[0], ys[0]))
                    }
                    for ((measured, offset) in axisLabels) {
                        drawText(measured, topLeft = offset)
                    }
                }
            }
    ) {
        val index = selectedIndex ?: return@Canvas
        if (index !in points.indices || toValues.size != points.size) return@Canvas
        val layout = chartLayout(size)
        val values = interpolate(fromValues, toValues, morph.value)
        val maxY = niceMax(lerpFloat(peak(fromValues), peak(toValues), morph.value))
        val x = layout.xAt(index, points.size)
        val y = layout.yAt(values[index], maxY)
        drawCursorAndTooltip(
            x = x,
            y = y,
            layout = layout,
            point = points[index],
            textMeasurer = textMeasurer,
            style = tooltipStyle,
            lineColor = lineColor,
            guideColor = guideColor,
            containerColor = tooltipContainerColor,
        )
    }
}

private fun DrawScope.drawCursorAndTooltip(
    x: Float,
    y: Float,
    layout: ChartLayout,
    point: BlockPoint,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    lineColor: Color,
    guideColor: Color,
    containerColor: Color,
) {
    drawLine(
        color = guideColor,
        start = Offset(x, layout.top),
        end = Offset(x, layout.bottom),
        strokeWidth = 1.dp.toPx(),
    )
    drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(x, y))
    drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x, y))

    val text = "${point.tooltipTitle}\n${point.count} ${attemptsWord(point.count)}"
    val measured = textMeasurer.measure(text, style)
    val paddingH = 10.dp.toPx()
    val paddingV = 6.dp.toPx()
    val gap = 10.dp.toPx()
    val boxWidth = measured.size.width + paddingH * 2f
    val boxHeight = measured.size.height + paddingV * 2f
    val boxLeft = (x - boxWidth / 2f).coerceIn(0f, (size.width - boxWidth).coerceAtLeast(0f))
    val aboveTop = y - gap - boxHeight
    val boxTop = if (aboveTop >= 0f) aboveTop else (y + gap).coerceAtMost(size.height - boxHeight)

    drawRoundRect(
        color = containerColor,
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
    val layout = chartLayout(Size(size.width.toFloat(), size.height.toFloat()))
    if (count == 1) return 0
    val fraction = ((x - layout.left) / (layout.right - layout.left)).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt()
}

/**
 * Монотонный кубический Эрмит (Фрич–Карлсон): касательные ограничиваются так, чтобы
 * сплайн не выходил за пределы соседних значений; каждый участок записывается как
 * кубическая кривая Безье с контрольными точками на трети шага по X.
 */
private fun buildMonotonePath(path: Path, xs: FloatArray, ys: FloatArray) {
    path.reset()
    val n = xs.size
    if (n == 0) return
    path.moveTo(xs[0], ys[0])
    if (n == 1) return

    val secants = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
    val tangents = FloatArray(n)
    tangents[0] = secants[0]
    tangents[n - 1] = secants[n - 2]
    for (i in 1 until n - 1) {
        tangents[i] = if (secants[i - 1] * secants[i] <= 0f) 0f else (secants[i - 1] + secants[i]) / 2f
    }
    for (i in 0 until n - 1) {
        if (secants[i] == 0f) {
            tangents[i] = 0f
            tangents[i + 1] = 0f
        } else {
            val a = tangents[i] / secants[i]
            val b = tangents[i + 1] / secants[i]
            val s = a * a + b * b
            if (s > 9f) {
                val tau = 3f / sqrt(s)
                tangents[i] = tau * a * secants[i]
                tangents[i + 1] = tau * b * secants[i]
            }
        }
    }
    for (i in 0 until n - 1) {
        val dx = (xs[i + 1] - xs[i]) / 3f
        path.cubicTo(
            xs[i] + dx, ys[i] + tangents[i] * dx,
            xs[i + 1] - dx, ys[i + 1] - tangents[i + 1] * dx,
            xs[i + 1], ys[i + 1],
        )
    }
}

private fun interpolate(from: FloatArray, to: FloatArray, t: Float): FloatArray {
    if (from.size != to.size) return to.copyOf()
    return FloatArray(to.size) { lerpFloat(from[it], to[it], t) }
}

/** Линейный пересэмплинг значений на новое число точек (для морфинга 24 ↔ 7 ↔ 30). */
private fun resample(values: FloatArray, count: Int): FloatArray {
    if (count == 0) return FloatArray(0)
    if (values.isEmpty()) return FloatArray(count)
    if (values.size == 1) return FloatArray(count) { values[0] }
    if (count == 1) return floatArrayOf(values.average().toFloat())
    return FloatArray(count) { index ->
        val position = index.toFloat() * (values.size - 1) / (count - 1)
        val lower = position.toInt().coerceAtMost(values.size - 2)
        lerpFloat(values[lower], values[lower + 1], position - lower)
    }
}

private fun peak(values: FloatArray): Float = values.maxOrNull() ?: 0f

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

/** Верхняя граница оси: не меньше 4 и с запасом 20%, чтобы пик не упирался в тултип. */
private fun niceMax(value: Float): Float = max(MIN_AXIS_MAX, ceil(value * AXIS_HEADROOM))

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

private const val MORPH_DURATION_MS = 650
private const val FILL_TOP_ALPHA = 0.35f
private const val MIN_AXIS_MAX = 4f
private const val AXIS_HEADROOM = 1.2f
