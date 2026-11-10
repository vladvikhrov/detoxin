package com.detoxin.app.ui.habits.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

object HabitProgressBarDefaults {
    val Height: Dp = 10.dp

    /** Доля выполненных привычек в `[0, 1]`; при `total <= 0` — ноль, без деления на ноль. */
    fun fraction(completed: Int, total: Int): Float =
        if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    fun percent(completed: Int, total: Int): Int = (fraction(completed, total) * 100f).roundToInt()
}

/**
 * Горизонтальная полоса заполнения «выполнено привычек / всего привычек».
 *
 * Рисуется на Canvas двумя скруглёнными прямоугольниками; ширина заполнения плавно анимируется
 * ([animateFloatAsState]) и читается только в фазе рисования — анимация не вызывает рекомпозиций.
 * Минимальная видимая ширина равна высоте полосы, чтобы первый шаг не выглядел точкой-обрубком.
 */
@Composable
fun HabitProgressBar(
    completed: Int,
    total: Int,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    height: Dp = HabitProgressBarDefaults.Height,
) {
    val target = HabitProgressBarDefaults.fraction(completed, total)
    val animated = animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "habitProgress",
    )
    Canvas(
        modifier = modifier
            .height(height)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
                stateDescription = "$completed из $total"
            },
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, size = size, cornerRadius = radius)
        val fraction = animated.value
        if (fraction > 0f) {
            val width = (size.width * fraction).coerceAtLeast(size.height).coerceAtMost(size.width)
            drawRoundRect(color = color, size = Size(width, size.height), cornerRadius = radius)
        }
    }
}
