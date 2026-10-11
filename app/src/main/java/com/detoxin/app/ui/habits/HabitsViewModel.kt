package com.detoxin.app.ui.habits

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.detoxin.app.bedtime.BedtimeHabitEvaluator
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.entity.HabitAutoSource
import com.detoxin.app.ui.widget.HabitGlanceWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
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
 *
 * Отметки задним числом: [toggleDay] принимает любой день в пределах
 * [HabitStatsCalculator.BACKFILL_WINDOW_DAYS]; будущие дни отклоняются. Снятие отметки
 * публикуется в [undoEvents] — экран предлагает «Отменить». Смена дня ([currentDay])
 * пересобирает состояние в полночь, даже если база не менялась.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitsViewModel(application: Application) : AndroidViewModel(application) {

    private val habitDao = DatabaseProvider.getDatabase(application).habitDao()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Текущая дата; эмитит заново сразу после локальной полуночи. */
    private val currentDay: Flow<LocalDate> = flow {
        while (true) {
            val today = LocalDate.now(zone)
            emit(today)
            val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            delay((nextMidnight - System.currentTimeMillis()).coerceAtLeast(0L) + MIDNIGHT_SLACK_MS)
        }
    }.distinctUntilChanged()

    private val undoChannel = Channel<UncheckedDay>(Channel.BUFFERED)

    /** Снятые отметки, которые экран предлагает вернуть. */
    val undoEvents: Flow<UncheckedDay> = undoChannel.receiveAsFlow()

    val uiState: StateFlow<HabitsUiState> = currentDay
        .flatMapLatest { today ->
            val windowStart = today.minusDays(HISTORY_WINDOW_DAYS).toEpochDay()
            combine(
                habitDao.observeHabitsWithLogs(windowStart, Long.MAX_VALUE),
                habitDao.observeLastCompletionDays(),
                habitDao.observeCycleCounts(),
            ) { habits, lastCompletions, cycleCounts ->
                HabitStatsCalculator.buildContent(
                    habits = habits,
                    lastCompletions = lastCompletions.associate { it.habitId to it.lastCompletedDay },
                    cycleCounts = cycleCounts.associate { it.habitId to it.completedInCycle },
                    today = today,
                    zone = zone,
                    locale = Locale.getDefault(),
                ) as HabitsUiState
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HabitsUiState.Loading)

    private val selectedHabitId = MutableStateFlow<Long?>(null)
    private val _detailRange = MutableStateFlow(HabitRange.WEEK)
    val detailRange: StateFlow<HabitRange> = _detailRange.asStateFlow()

    val detailState: StateFlow<HabitDetailUiState?> = combine(selectedHabitId, currentDay, ::Pair)
        .flatMapLatest { (habitId, today) ->
            if (habitId == null) return@flatMapLatest flowOf(null)
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
                    today = today,
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

    fun toggleToday(habitId: Long) = toggleDay(habitId, LocalDate.now(zone).toEpochDay())

    /**
     * Отметка или снятие отметки за [epochDay]: сегодня или до [HabitStatsCalculator.BACKFILL_WINDOW_DAYS]
     * дней назад. Будущие дни и дни за пределами окна игнорируются.
     */
    fun toggleDay(habitId: Long, epochDay: Long) {
        val today = LocalDate.now(zone).toEpochDay()
        if (epochDay > today || epochDay < today - HabitStatsCalculator.BACKFILL_WINDOW_DAYS) return
        write {
            val isCompleted = habitDao.toggleHabit(habitId, epochDay)
            if (!isCompleted) {
                val title = habitDao.getHabitById(habitId)?.title.orEmpty()
                undoChannel.send(UncheckedDay(habitId = habitId, habitTitle = title, epochDay = epochDay))
            }
        }
    }

    /** «Отменить» после снятия отметки: идемпотентно возвращает отметку. */
    fun undoUncheck(day: UncheckedDay) = write {
        habitDao.setCompletion(day.habitId, day.epochDay, isCompleted = true)
    }

    fun addHabit(title: String, targetDaysPerWeek: Int, colorHex: Long, autoSource: HabitAutoSource?) = write {
        habitDao.insertHabit(
            Habit(
                title = title.trim(),
                targetDaysPerWeek = targetDaysPerWeek.coerceIn(1, 7),
                colorHex = colorHex,
                createdAt = System.currentTimeMillis(),
                autoSource = autoSource?.key,
            )
        )
        // Если прошлая ночь уже прошла «чисто», автопривычка сразу получает сегодняшнюю отметку.
        if (autoSource == HabitAutoSource.BEDTIME) {
            BedtimeHabitEvaluator.evaluate(getApplication(), System.currentTimeMillis(), zone)
        }
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

    /**
     * Принудительная перерисовка каждого экземпляра виджета привычки. Выполняется после любой
     * записи, а не только за сегодня: виджет показывает и прогресс цикла, в который входят
     * отметки за прошлые дни.
     */
    private suspend fun refreshWidgets() {
        HabitGlanceWidget.refreshAll(getApplication<Application>())
    }

    private companion object {
        const val TAG = "HabitsViewModel"
        const val HISTORY_WINDOW_DAYS = 400L
        const val STOP_TIMEOUT_MS = 5_000L
        const val MIDNIGHT_SLACK_MS = 1_000L
    }
}
