package com.detoxin.app.ui.habits.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.detoxin.app.ui.habits.DayMark
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Полоса из 7 последних дней на карточке привычки: сплошная шкала из семи сегментов, где
 * выполненные дни залиты цветом привычки. Касание сегмента отмечает или снимает отметку за
 * этот день. Каждый день — отдельный чекбокс для TalkBack («Пятница, 10 октября, выполнено»),
 * область касания — вся колонка (не меньше 48dp по высоте).
 */
@Composable
fun DayMarkStrip(
    days: List<DayMark>,
    color: Color,
    onToggle: (epochDay: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
        days.forEachIndexed { index, day ->
            val spoken = dayDescription(day.epochDay)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = day.isCompleted,
                        role = Role.Checkbox,
                        onValueChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggle(day.epochDay)
                        },
                    )
                    .semantics {
                        contentDescription = spoken
                        stateDescription = if (day.isCompleted) "выполнено" else "не выполнено"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = day.weekdayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (day.isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DaySegment(
                    isCompleted = day.isCompleted,
                    isPartial = day.isPartial,
                    isToday = day.isToday,
                    color = color,
                    shape = segmentShape(index, days.lastIndex),
                )
            }
        }
    }
}

/** Крайние сегменты скруглены снаружи: семь сегментов читаются как одна полоса. */
private fun segmentShape(index: Int, lastIndex: Int): RoundedCornerShape {
    val outer = SEGMENT_HEIGHT / 2
    val inner = 2.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        bottomStart = if (index == 0) outer else inner,
        topEnd = if (index == lastIndex) outer else inner,
        bottomEnd = if (index == lastIndex) outer else inner,
    )
}

@Composable
private fun DaySegment(isCompleted: Boolean, isPartial: Boolean, isToday: Boolean, color: Color, shape: RoundedCornerShape) {
    val fill by animateColorAsState(
        targetValue = when {
            isCompleted -> color
            isPartial -> color.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(FILL_ANIMATION_MS),
        label = "daySegment",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEGMENT_HEIGHT)
            .background(fill, shape)
            .then(if (isToday && !isCompleted) Modifier.border(1.5.dp, color, shape) else Modifier),
    )
}

/** «Пятница, 10 октября» — для TalkBack и подписей. */
internal fun dayDescription(epochDay: Long, locale: Locale = Locale.getDefault()): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { it.titlecase(locale) }
    return "$weekday, ${date.format(DateTimeFormatter.ofPattern("d MMMM", locale))}"
}

private val SEGMENT_HEIGHT = 10.dp
private val SEGMENT_GAP = 3.dp
private const val FILL_ANIMATION_MS = 250
