package com.detoxin.app.ui.share

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.detoxin.app.bedtime.BedtimeRepository
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.entity.BlockType
import com.detoxin.app.ui.habits.HabitStatsCalculator
import com.detoxin.app.ui.score.DetoxScoreCalculator
import com.detoxin.app.util.DayRollover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SharePeriod(val title: String, val days: Int, val caption: String) {
    WEEK("Неделя", 7, "Итоги недели"),
    MONTH("Месяц", 30, "Итоги месяца"),
}

/**
 * Данные карточки итогов.
 *
 * @property savedMs на сколько экранное время меньше, чем за предыдущий такой же период;
 * `null` — сравнивать не с чем (нет данных за прошлый период) или экономии нет.
 */
@Immutable
data class ShareCardStats(
    val period: SharePeriod,
    val dateRange: String,
    val screenTimeMs: Long,
    val savedMs: Long?,
    val blockedShorts: Int,
    val blockedTotal: Int,
    val bestStreak: Int,
    val score: Int,
)

/** Сводка для карточки итогов недели/месяца: всё считается из локальной базы. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareCardViewModel(application: Application) : AndroidViewModel(application) {

    private val database = DatabaseProvider.getDatabase(application)

    private val _period = MutableStateFlow(SharePeriod.WEEK)
    val period: StateFlow<SharePeriod> = _period.asStateFlow()

    val stats: StateFlow<ShareCardStats?> = combine(_period, DayRollover.dayFlow(application), ::Pair)
        .flatMapLatest { (period, today) ->
            val zone = ZoneId.systemDefault()
            val start = today.minusDays(period.days - 1L)
            val previousStart = start.minusDays(period.days.toLong())
            val scoreStart = today.minusDays(DetoxScoreCalculator.WINDOW_DAYS.toLong())
            val scoreStartWall = scoreStart.atStartOfDay(zone).toInstant().toEpochMilli()
            combine(
                database.usageDao().observeLogsInRange(previousStart.toEpochDay(), today.toEpochDay()),
                database.blockedAttemptDao().observeAttemptsInRange(start.toEpochDay(), today.toEpochDay()),
                database.habitDao().observeHabitsWithLogs(today.minusDays(STREAK_WINDOW_DAYS).toEpochDay(), today.toEpochDay()),
                database.protectionGapDao().observeOverlapping(scoreStartWall, Long.MAX_VALUE),
                BedtimeRepository.configFlow(application),
            ) { logs, attempts, habits, gaps, bedtime ->
                val now = System.currentTimeMillis()
                val current = logs.filter { it.dateEpochDay >= start.toEpochDay() }.sumOf { it.durationMs }
                val previousLogs = logs.filter { it.dateEpochDay < start.toEpochDay() }
                val previous = previousLogs.sumOf { it.durationMs }
                val format = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                ShareCardStats(
                    period = period,
                    dateRange = "${start.format(format)} – ${today.format(format)}",
                    screenTimeMs = current,
                    savedMs = (previous - current).takeIf { previousLogs.isNotEmpty() && it > 0L },
                    blockedShorts = attempts.count { it.blockType in shortsTypes },
                    blockedTotal = attempts.size,
                    bestStreak = habits.maxOfOrNull { item ->
                        val completed = item.logs.filter { it.isCompleted }.map { it.dateEpochDay }.toHashSet()
                        HabitStatsCalculator.currentStreak(completed, today.toEpochDay())
                    } ?: 0,
                    score = DetoxScoreCalculator.compute(
                        today = today,
                        zone = zone,
                        nowWall = now,
                        bedtime = bedtime.effectiveAt(now),
                        logs = logs,
                        gaps = gaps,
                        habits = habits,
                    ).total,
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun selectPeriod(period: SharePeriod) {
        _period.value = period
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val STREAK_WINDOW_DAYS = 400L
        val shortsTypes = setOf(BlockType.SHORTS, BlockType.BROWSER_SHORTS)
    }
}
