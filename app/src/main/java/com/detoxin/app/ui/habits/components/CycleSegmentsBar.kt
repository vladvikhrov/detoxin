package com.detoxin.app.ui.habits.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Линейный мини-индикатор цикла из [segments] делений (по умолчанию 30) — компактная
 * пара к кольцу виджета. Позиции сегментов считаются в `drawWithCache`; число
 * закрашенных плавно анимируется при отметке или сбросе цикла.
 */
@Composable
fun CycleSegmentsBar(
    progress: Int,
    cycleLength: Int,
    color: Color,
    modifier: Modifier = Modifier,
    segments: Int = 30,
    trackColor: Color = Color(0xFF2A313A),
) {
    val target = if (cycleLength <= 0) 0f else progress.coerceIn(0, cycleLength) * segments / cycleLength.toFloat()
    val animatedFilled by animateFloatAsState(target, animationSpec = tween(500), label = "cycleFill")

    Canvas(
        modifier = modifier.drawWithCache {
            val gap = 2.dp.toPx()
            val segmentWidth = ((size.width - gap * (segments - 1)) / segments).coerceAtLeast(1f)
            val radius = CornerRadius(size.height / 2f)
            val segmentSize = Size(segmentWidth, size.height)
            val lefts = FloatArray(segments) { it * (segmentWidth + gap) }
            onDrawBehind {
                for (index in 0 until segments) {
                    val fill = (animatedFilled - index).coerceIn(0f, 1f)
                    val segmentColor = if (fill > 0f) color.copy(alpha = 0.35f + 0.65f * fill) else trackColor
                    drawRoundRect(segmentColor, Offset(lefts[index], 0f), segmentSize, radius)
                }
            }
        }
    ) {}
}
