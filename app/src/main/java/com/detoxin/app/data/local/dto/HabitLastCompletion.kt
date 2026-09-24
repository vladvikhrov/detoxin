package com.detoxin.app.data.local.dto

/** Последний день (`epochDay`), когда привычка была выполнена. */
data class HabitLastCompletion(
    val habitId: Long,
    val lastCompletedDay: Long,
)
