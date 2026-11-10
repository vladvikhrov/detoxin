package com.detoxin.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Отметка привычки за конкретный день.
 *
 * Уникальный индекс `(habitId, dateEpochDay)` гарантирует не более одной записи
 * на привычку в день — на нём построен атомарный toggle в
 * [com.detoxin.app.data.local.dao.HabitDao.toggleHabit].
 * При удалении [Habit] все его логи удаляются каскадно.
 *
 * [slotsDone] — битовая маска отметок двухразовой привычки ([Habit.SLOT_MORNING],
 * [Habit.SLOT_EVENING]); у обычной привычки не используется. [isCompleted] у двухразовой
 * привычки равен `true` только при обеих отметках, поэтому вся статистика считает дни как раньше.
 */
@Entity(
    tableName = "habit_logs",
    foreignKeys = [
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["habitId"]),
        Index(value = ["habitId", "dateEpochDay"], unique = true),
    ],
)
data class HabitLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val habitId: Long,
    val dateEpochDay: Long,
    val isCompleted: Boolean,
    @ColumnInfo(defaultValue = "0")
    val slotsDone: Int = 0,
)
