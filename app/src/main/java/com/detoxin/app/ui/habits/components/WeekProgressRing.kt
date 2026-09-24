package com.detoxin.app.ui.habits.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** Мини-кольцо нормы недели: дуга `completed / target` и подпись «3/5» в центре. */
@Composable
fun WeekProgressRing(
    completed: Int,
    target: Int,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = textColor)
    val fraction = if (target <= 0) 0f else (completed.toFloat() / target).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(fraction, animationSpec = tween(500), label = "weekRing")

    Canvas(
        modifier = modifier.drawWithCache {
            val strokeWidth = 4.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val label = textMeasurer.measure("$completed/$target", style)
            val labelOffset = Offset(
                (size.width - label.size.width) / 2f,
                (size.height - label.size.height) / 2f,
            )
            onDrawBehind {
                drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
                if (animatedFraction > 0f) {
                    drawArc(color, -90f, 360f * animatedFraction, false, Offset(inset, inset), arcSize, style = stroke)
                }
                drawText(label, topLeft = labelOffset)
            }
        }
    ) {}
}
