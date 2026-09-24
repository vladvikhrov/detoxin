package com.detoxin.app.ui.habits

import androidx.compose.runtime.Immutable

/** Диапазон детальной статистики привычки. */
enum class HabitRange(val title: String) {
    WEEK("Неделя"),
    MONTH("Месяц"),
    YEAR("Год"),
}

/** Карточка привычки в списке. */
@Immutable
data class HabitItemUi(
    val id: Long,
    val title: String,
    val colorHex: Long,
    val targetDaysPerWeek: Int,
    val completedToday: Boolean,
    val currentStreak: Int,
    val completedThisWeek: Int,
    val cycleProgress: Int,
    val cycleLength: Int,
)

/**
 * Темп месяц-к-месяцу. Доли — выполнение нормы (`targetDaysPerWeek`) за активные дни периода.
 * @property deltaPoints изменение в процентных пунктах; `null`, если прошлый месяц не с чем сравнить.
 */
@Immutable
data class PaceOverview(
    val currentMonthRate: Float?,
    val previousMonthRate: Float?,
    val deltaPoints: Int?,
)

@Immutable
data class AbandonedHabit(
    val habitId: Long,
    val title: String,
    val daysSinceLastCompletion: Int,
)

sealed interface HabitsUiState {
    data object Loading : HabitsUiState

    @Immutable
    data class Content(
        val overview: PaceOverview,
        val abandoned: List<AbandonedHabit>,
        val habits: List<HabitItemUi>,
    ) : HabitsUiState
}

/** Столбец динамики: [value] — доля выполнения в `[0, 1]`, [isFuture] — период ещё не наступил. */
@Immutable
data class TrendBar(
    val label: String,
    val value: Float,
    val isFuture: Boolean,
)

/** Доля выполнения для одного дня недели; `null` — день ни разу не попал в интервал. */
@Immutable
data class WeekdayStat(
    val label: String,
    val ratio: Float?,
)

@Immutable
data class HabitDetailUiState(
    val habitId: Long,
    val title: String,
    val colorHex: Long,
    val targetDaysPerWeek: Int,
    val range: HabitRange,
    val bars: List<TrendBar>,
    val weekdays: List<WeekdayStat>,
    val completedInRange: Int,
    val possibleInRange: Int,
    val currentStreak: Int,
    val totalCompletions: Int,
    val lastCompletedDay: Long?,
    val cycleProgress: Int,
    val cycleLength: Int,
    /** День начала текущего цикла; `null` — цикл идёт с момента создания привычки. */
    val cycleStartDay: Long?,
)
