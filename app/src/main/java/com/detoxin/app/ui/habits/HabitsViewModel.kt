package com.detoxin.app.ui.habits

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.ui.widget.HabitGlanceWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Хаб привычек: список со статусами, темп месяц-к-месяцу, заброшенные привычки и детальная
 * статистика выбранной привычки.
 *
 * Список строится из одного реактивного окна логов за [HISTORY_WINDOW_DAYS] дней (стрики,
 * неделя, текущий и прошлый месяц) и Flow последних выполнений. Любая запись — из этого
 * экрана, виджета или другой вкладки — пересчитывает состояние через инвалидацию Room.
 * Агрегация идёт на Dispatchers.Default; записи — на Dispatchers.IO с обновлением виджетов.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitsViewModel(application: Application) : AndroidViewModel(application) {

    private val habitDao = DatabaseProvider.getDatabase(application).habitDao()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val uiState: StateFlow<HabitsUiState> = flow {
        val windowStart = LocalDate.now(zone).minusDays(HISTORY_WINDOW_DAYS).toEpochDay()
        emit(windowStart)
    }
        .flatMapLatest { windowStart ->
            combine(
                habitDao.observeHabitsWithLogs(windowStart, Long.MAX_VALUE),
                habitDao.observeLastCompletionDays(),
                habitDao.observeCycleCounts(),
            ) { habits, lastCompletions, cycleCounts ->
                HabitStatsCalculator.buildContent(
                    habits = habits,
                    lastCompletions = lastCompletions.associate { it.habitId to it.lastCompletedDay },
                    cycleCounts = cycleCounts.associate { it.habitId to it.completedInCycle },
                    today = LocalDate.now(zone),
                    zone = zone,
                ) as HabitsUiState
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HabitsUiState.Loading)

    private val selectedHabitId = MutableStateFlow<Long?>(null)
    private val _detailRange = MutableStateFlow(HabitRange.WEEK)
    val detailRange: StateFlow<HabitRange> = _detailRange.asStateFlow()

    val detailState: StateFlow<HabitDetailUiState?> = selectedHabitId
        .flatMapLatest { habitId ->
            if (habitId == null) return@flatMapLatest flowOf(null)
            val today = LocalDate.now(zone)
            val windowStart = HabitStatsCalculator.detailWindowStart(today).toEpochDay()
            combine(
                habitDao.observeHabit(habitId),
                habitDao.getLogsForHabit(habitId, windowStart, Long.MAX_VALUE),
                _detailRange,
            ) { habit, logs, range ->
                if (habit == null) return@combine null
                HabitStatsCalculator.buildDetail(
                    habit = habit,
                    logs = logs,
                    range = range,
                    today = LocalDate.now(zone),
                    zone = zone,
                    totalCompletions = habitDao.getCompletionCount(habitId, Long.MIN_VALUE, Long.MAX_VALUE),
                    lastCompletedDay = habitDao.getLastCompletionDay(habitId),
                    cycleCount = habitDao.getCompletionCount(habitId, habit.cycleResetEpochDay, Long.MAX_VALUE),
                    locale = Locale.getDefault(),
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun openDetail(habitId: Long) {
        _detailRange.value = HabitRange.WEEK
        selectedHabitId.value = habitId
    }

    fun closeDetail() {
        selectedHabitId.value = null
    }

    fun selectRange(range: HabitRange) {
        _detailRange.value = range
    }

    fun toggleToday(habitId: Long) = write {
        habitDao.toggleHabit(habitId, LocalDate.now(zone).toEpochDay())
    }

    fun addHabit(title: String, targetDaysPerWeek: Int, colorHex: Long) = write {
        habitDao.insertHabit(
            Habit(
                title = title.trim(),
                targetDaysPerWeek = targetDaysPerWeek.coerceIn(1, 7),
                colorHex = colorHex,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    fun updateTarget(habitId: Long, targetDaysPerWeek: Int) = write {
        habitDao.updateTargetDaysPerWeek(habitId, targetDaysPerWeek.coerceIn(1, 7))
    }

    /** Новый цикл (замена линз): отсчёт с сегодняшнего дня включительно. */
    fun resetCycle(habitId: Long) = write {
        habitDao.resetHabitCycle(habitId, LocalDate.now(zone).toEpochDay())
    }

    fun deleteHabit(habitId: Long) {
        if (selectedHabitId.value == habitId) selectedHabitId.value = null
        write { habitDao.deleteHabitById(habitId) }
    }

    /** Запись в Room на IO и немедленное обновление всех виджетов привычек. */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: SQLiteException) {
                Log.e(TAG, "Habit write failed", e)
                return@launch
            }
            refreshWidgets()
        }
    }

    /** Принудительная перерисовка каждого экземпляра виджета привычки. */
    private suspend fun refreshWidgets() {
        val context = getApplication<Application>()
        val widget = HabitGlanceWidget()
        GlanceAppWidgetManager(context).getGlanceIds(HabitGlanceWidget::class.java).forEach { glanceId ->
            widget.update(context, glanceId)
        }
    }

    private companion object {
        const val TAG = "HabitsViewModel"
        const val HISTORY_WINDOW_DAYS = 400L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
