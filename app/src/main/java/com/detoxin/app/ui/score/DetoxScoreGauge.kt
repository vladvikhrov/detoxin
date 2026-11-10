package com.detoxin.app.ui.score

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Полукруглый циферблат Detox Score на чистом Canvas.
 *
 * Дуга 180° с градиентом от красного через жёлтый к зелёному, заполнение и стрелка плавно
 * доезжают до значения ([animateFloatAsState]). Геометрия и градиент кэшируются в
 * `drawWithCache`; анимируемая доля читается только в фазе рисования.
 */
@Composable
fun DetoxScoreGauge(
    score: Int,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    needleColor: Color = MaterialTheme.colorScheme.onSurface,
    numberStyle: TextStyle = MaterialTheme.typography.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    ),
) {
    val clamped = score.coerceIn(0, 100)
    val fraction = animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "detoxScore",
    )
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .aspectRatio(2f)
            .semantics { contentDescription = "Detox Score: $clamped из 100" }
            .drawWithCache {
                val strokeWidth = size.height * 0.16f
                val radius = minOf(size.width / 2f, size.height) - strokeWidth / 2f
                val center = Offset(size.width / 2f, size.height - strokeWidth / 2f)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                // sweepGradient отсчитывает угол от «3 часов» по часовой стрелке: дуга шкалы занимает 180°–360°.
                // Нижняя половина круга не рисуется, но в неё заходят скруглённые концы дуги,
                // поэтому рядом с 0.5 держим красный, а рядом с 0.0 зелёный.
                val gradient = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0.00f to GaugeGreen,
                        0.10f to GaugeGreen,
                        0.40f to GaugeRed,
                        0.50f to GaugeRed,
                        0.75f to GaugeYellow,
                        1.00f to GaugeGreen,
                    ),
                    center = center,
                )
                val number = textMeasurer.measure(clamped.toString(), numberStyle)
                val markerRadius = strokeWidth * 0.62f

                onDrawBehind {
                    drawArc(trackColor, 180f, 180f, false, arcTopLeft, arcSize, style = stroke)
                    val value = fraction.value
                    if (value > 0f) {
                        drawArc(gradient, 180f, 180f * value, false, arcTopLeft, arcSize, style = stroke)
                    }
                    // Маркер на дуге вместо стрелки: стрелка из центра перечёркивала число на средних значениях.
                    val angle = (180f + 180f * value) * (PI.toFloat() / 180f)
                    val marker = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
                    drawCircle(needleColor, markerRadius, marker)
                    drawCircle(gaugeColorAt(value), markerRadius - 3.dp.toPx(), marker)
                    drawText(
                        number,
                        topLeft = Offset(center.x - number.size.width / 2f, center.y - radius * 0.35f - number.size.height / 2f),
                    )
                }
            },
    ) {}
}

private fun gaugeColorAt(fraction: Float): Color =
    if (fraction < 0.5f) lerp(GaugeRed, GaugeYellow, fraction * 2f)
    else lerp(GaugeYellow, GaugeGreen, (fraction - 0.5f) * 2f)

private val GaugeRed = Color(0xFFD64545)
private val GaugeYellow = Color(0xFFE0A91F)
private val GaugeGreen = Color(0xFF2E9E5B)
