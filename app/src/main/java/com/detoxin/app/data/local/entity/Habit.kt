package com.detoxin.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Отслеживаемая привычка.
 * [colorHex] хранит ARGB-цвет как Long (например, `0xFF4CAF50`), чтобы значение
 * без потерь передавалось в `androidx.compose.ui.graphics.Color(ULong/Long)`.
 *
 * Цикл использования (например, 30-дневные линзы): прогресс цикла — число выполненных
 * дней начиная с [cycleResetEpochDay] включительно; `0` означает «с момента создания».
 * `defaultValue` совпадает с DDL миграций 1→2 и 2→3, иначе валидация схемы Room не пройдёт.
 *
 * [autoSource] — источник автоматических отметок ([HabitAutoSource]); `null` — только ручные.
 */
@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val targetDaysPerWeek: Int,
    val colorHex: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "30")
    val cycleLength: Int = DEFAULT_CYCLE_LENGTH,
    @ColumnInfo(defaultValue = "0")
    val cycleResetEpochDay: Long = 0L,
    @ColumnInfo(defaultValue = "NULL")
    val autoSource: String? = null,
) {
    companion object {
        const val DEFAULT_CYCLE_LENGTH = 30
    }
}

/**
 * Источники автоматических отметок. В базе хранится стабильная строка [key], а не `ordinal`.
 * Ручная отметка всегда приоритетнее: автоматика только добавляет отметку за день, по которому
 * ещё нет никакой записи.
 */
enum class HabitAutoSource(val key: String) {
    /** «Лёг вовремя»: ночь прошла в пределах ночного лимита и без отключения защиты. */
    BEDTIME("BEDTIME");

    companion object {
        fun fromKey(key: String?): HabitAutoSource? = entries.firstOrNull { it.key == key }
    }
}
