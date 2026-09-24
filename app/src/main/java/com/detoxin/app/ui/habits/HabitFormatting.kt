package com.detoxin.app.ui.habits

import androidx.compose.ui.graphics.Color

/** Палитра привычек (ARGB) — для диалога создания и значения по умолчанию. */
internal val HabitPalette: List<Long> = listOf(
    0xFF1B7F4BL,
    0xFF2E7DD7L,
    0xFF7B5CD6L,
    0xFFD6457BL,
    0xFFE07A1FL,
    0xFFC9A227L,
    0xFF1C9AA0L,
    0xFF6D7A88L,
)

internal fun habitColor(colorHex: Long): Color = Color(colorHex)

/** Русское согласование: 1 день, 2 дня, 5 дней. */
internal fun daysWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
}

/** Текст предупреждения о заброшенной привычке. */
internal fun abandonedMessage(habit: AbandonedHabit): String {
    val period = if (habit.daysSinceLastCompletion < 14) {
        "уже неделю"
    } else {
        "уже ${habit.daysSinceLastCompletion} ${daysWord(habit.daysSinceLastCompletion)}"
    }
    return "Вы $period не делаете «${habit.title}». Пора вернуться или удалить привычку."
}
