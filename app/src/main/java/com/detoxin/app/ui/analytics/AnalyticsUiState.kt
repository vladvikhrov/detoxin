package com.detoxin.app.ui.analytics

import androidx.compose.runtime.Immutable

/** Диапазон аналитики. «День» разбивается по часам, «Неделя» и «Месяц» — по дням. */
enum class AnalyticsRange(val title: String, val days: Int) {
    DAY("День", 1),
    WEEK("Неделя", 7),
    MONTH("Месяц", 30),
}

/** Столбец экранного времени за один бакет (час или день). */
@Immutable
data class UsageBar(
    val label: String,
    val tikTokMs: Long,
    val youTubeMs: Long,
    val otherMs: Long,
) {
    val totalMs: Long get() = tikTokMs + youTubeMs + otherMs
}

/** @property limitMs лимит экранного времени на один бакет (пунктирная линия). */
@Immutable
data class UsageChartData(
    val bars: List<UsageBar>,
    val limitMs: Long,
)

/** Точка кривой спасённых открытий. [tooltipTitle] — дата/час для тултипа. */
@Immutable
data class BlockPoint(
    val axisLabel: String,
    val tooltipTitle: String,
    val count: Int,
)

/**
 * Ячейка тепловой карты привычек.
 * @property completionRatio доля выполненных привычек за день в `[0, 1]`;
 * `null` — в этот день не было ни одной привычки.
 * @property inRange день входит в выбранный интервал (остальные дорисовывают неделю до Пн–Вс).
 */
@Immutable
data class HeatmapCell(
    val dateEpochDay: Long,
    val completionRatio: Float?,
    val inRange: Boolean,
)

/** Колонки — недели, внутри колонки ровно 7 ячеек с понедельника по воскресенье. */
@Immutable
data class HeatmapData(
    val weeks: List<List<HeatmapCell>>,
)

@Immutable
data class AnalyticsSummary(
    val totalScreenTimeMs: Long,
    val totalBlocked: Int,
    val averageHabitCompletion: Float?,
)

sealed interface AnalyticsUiState {
    data object Loading : AnalyticsUiState

    data class Empty(val range: AnalyticsRange) : AnalyticsUiState

    @Immutable
    data class Success(
        val range: AnalyticsRange,
        val summary: AnalyticsSummary,
        val usage: UsageChartData,
        val blocks: List<BlockPoint>,
        val heatmap: HeatmapData,
    ) : AnalyticsUiState
}
