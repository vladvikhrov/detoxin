package com.detoxin.app.data.local.dto

/** Число выполненных дней в текущем цикле привычки (с `cycleResetEpochDay` включительно). */
data class HabitCycleCount(
    val habitId: Long,
    val completedInCycle: Int,
)
