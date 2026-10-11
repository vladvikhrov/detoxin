package com.detoxin.app.ui.habits.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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
 * Ряд из 7 последних дней на карточке привычки: касание отмечает или снимает отметку за
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
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { day ->
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
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = day.weekdayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (day.isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DayCircle(isCompleted = day.isCompleted, isToday = day.isToday, color = color)
            }
        }
    }
}

@Composable
private fun DayCircle(isCompleted: Boolean, isToday: Boolean, color: Color) {
    val outline = if (isToday) 2.dp else 1.5.dp
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(if (isCompleted) color else Color.Transparent, CircleShape)
            .border(outline, color.copy(alpha = if (isCompleted || isToday) 1f else OUTLINE_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (isCompleted) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** «Пятница, 10 октября» — для TalkBack и подписей. */
internal fun dayDescription(epochDay: Long, locale: Locale = Locale.getDefault()): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { it.titlecase(locale) }
    return "$weekday, ${date.format(DateTimeFormatter.ofPattern("d MMMM", locale))}"
}

private const val OUTLINE_ALPHA = 0.55f
