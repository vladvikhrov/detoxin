package com.detoxin.app.ui.habits.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Календарь месяца для отметок задним числом: сетка 7×N, неделя с понедельника.
 *
 * Выполненный день — залитый круг цвета привычки, невыполненный — контур; будущие дни и дни
 * раньше [backfillFromEpochDay] неактивны (alpha 0.38). Стрелки листают месяцы только в
 * пределах окна отметок. Выбранный месяц переживает поворот экрана.
 */
@Composable
fun HabitCalendar(
    completedDays: Set<Long>,
    todayEpochDay: Long,
    backfillFromEpochDay: Long,
    color: Color,
    onToggle: (epochDay: Long) -> Unit,
    modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
) {
    val haptics = LocalHapticFeedback.current
    val currentMonth = YearMonth.from(LocalDate.ofEpochDay(todayEpochDay))
    val earliestMonth = YearMonth.from(LocalDate.ofEpochDay(backfillFromEpochDay))
    val minOffset = -ChronoUnit.MONTHS.between(earliestMonth, currentMonth).toInt()
    var storedOffset by rememberSaveable { mutableIntStateOf(0) }
    // Окно отметок сдвигается вместе с датой: сохранённый месяц мог выйти за его пределы.
    val monthOffset = storedOffset.coerceIn(minOffset, 0)
    val month = currentMonth.plusMonths(monthOffset.toLong())

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { storedOffset = monthOffset - 1 }, enabled = monthOffset > minOffset) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий месяц")
            }
            Text(
                text = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                    .replaceFirstChar { it.titlecase(locale) } + " ${month.year}",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { storedOffset = monthOffset + 1 }, enabled = monthOffset < 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий месяц")
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { dayOfWeek ->
                Text(
                    text = dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val first = month.atDay(1)
        val leadingBlanks = first.dayOfWeek.value - DayOfWeek.MONDAY.value
        val cellCount = leadingBlanks + month.lengthOfMonth()
        val weeks = (cellCount + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK
        for (week in 0 until weeks) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until DAYS_PER_WEEK) {
                    val dayOfMonth = week * DAYS_PER_WEEK + column - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayOfMonth in 1..month.lengthOfMonth()) {
                            val epochDay = month.atDay(dayOfMonth).toEpochDay()
                            CalendarDay(
                                dayOfMonth = dayOfMonth,
                                epochDay = epochDay,
                                isCompleted = epochDay in completedDays,
                                isToday = epochDay == todayEpochDay,
                                enabled = epochDay in backfillFromEpochDay..todayEpochDay,
                                color = color,
                                onToggle = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onToggle(epochDay)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    dayOfMonth: Int,
    epochDay: Long,
    isCompleted: Boolean,
    isToday: Boolean,
    enabled: Boolean,
    color: Color,
    onToggle: () -> Unit,
) {
    val spoken = dayDescription(epochDay)
    Box(
        modifier = Modifier
            .padding(3.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .background(if (isCompleted) color else Color.Transparent, CircleShape)
            .border(
                width = if (isToday) 2.dp else 1.dp,
                color = if (isCompleted || isToday) color else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .toggleable(
                value = isCompleted,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .semantics {
                contentDescription = spoken
                stateDescription = if (isCompleted) "выполнено" else "не выполнено"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isCompleted) Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val DAYS_PER_WEEK = 7
private const val DISABLED_ALPHA = 0.38f
