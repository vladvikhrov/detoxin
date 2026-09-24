package com.detoxin.app.data.local.dto

import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.entity.HabitLog

/**
 * Привычка вместе с логами, отфильтрованными по запрошенному интервалу дат.
 * Привычка без отметок в интервале возвращается с пустым [logs].
 */
data class HabitWithLogs(
    val habit: Habit,
    val logs: List<HabitLog>,
)
