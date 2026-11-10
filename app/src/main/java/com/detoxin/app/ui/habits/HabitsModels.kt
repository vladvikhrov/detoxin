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
    /** Последние 7 дней (сегодня — последний) для быстрой отметки прошедших дней. */
    val lastSevenDays: List<DayMark>,
    /** Привычка отмечается автоматически («Лёг вовремя»). */
    val isAuto: Boolean,
    /** 1 — обычная, 2 — утром и вечером. */
    val timesPerDay: Int = 1,
    /** Маска отметок за сегодня ([com.detoxin.app.data.local.entity.Habit.SLOT_MORNING] и т. д.). */
    val todaySlots: Int = 0,
)

/** День в ряду быстрой отметки. [weekdayLabel] — «Пн», «Вт»… */
@Immutable
data class DayMark(
    val epochDay: Long,
    val weekdayLabel: String,
    val isCompleted: Boolean,
    val isToday: Boolean,
    /** Двухразовая привычка отмечена только один раз. */
    val isPartial: Boolean = false,
)

/** Снятая отметка, которую можно вернуть кнопкой «Отменить» в Snackbar. */
@Immutable
data class UncheckedDay(
    val habitId: Long,
    val habitTitle: String,
    val epochDay: Long,
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

/**
 * Точка линии динамики: [value] — доля выполнения в `[0, 1]`; `null` — период ещё не наступил
 * или привычки тогда не было (линия обрывается, а не падает в ноль).
 */
@Immutable
data class TrendPoint(
    val label: String,
    val tooltipTitle: String,
    val value: Float?,
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
    val trend: List<TrendPoint>,
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
    /** Выполненные дни в загруженном окне — для календаря отметок. */
    val completedDays: Set<Long>,
    val todayEpochDay: Long,
    /** Самый ранний день, который можно отметить задним числом. */
    val backfillFromEpochDay: Long,
    val isAuto: Boolean,
)
