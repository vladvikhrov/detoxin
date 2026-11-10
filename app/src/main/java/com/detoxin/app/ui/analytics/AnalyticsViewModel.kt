package com.detoxin.app.ui.analytics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.detoxin.app.bedtime.BedtimeRepository
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.usage.UsageSync
import com.detoxin.app.ui.score.DetoxScore
import com.detoxin.app.ui.score.DetoxScoreCalculator
import com.detoxin.app.util.DayRollover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Единая точка данных экрана аналитики.
 *
 * Смена диапазона через [flatMapLatest] отменяет подписки на старые запросы Room и
 * открывает новые. Room выполняет запросы на своём executor-е, [combine] и агрегация
 * идут на [Dispatchers.Default]. Промежуточный Loading при смене диапазона не выдаётся:
 * экран держит старые данные, пока не придут новые.
 *
 * Карточка «Ночное время» ([nightState]) живёт отдельным потоком: она зависит ещё и от настроек
 * «Отбоя» и при выключенном режиме равна `null`.
 */
class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = DatabaseProvider.getDatabase(application)
    private val usageDao = database.usageDao()
    private val blockedAttemptDao = database.blockedAttemptDao()
    private val habitDao = database.habitDao()
    private val protectionGapDao = database.protectionGapDao()

    private val _selectedRange = MutableStateFlow(AnalyticsRange.WEEK)
    val selectedRange: StateFlow<AnalyticsRange> = _selectedRange.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<AnalyticsUiState> = combine(_selectedRange, DayRollover.dayFlow(application), ::Pair)
        .flatMapLatest { (range, today) ->
            // Дата приходит из потока смены дня: открытый в полночь экран сам переходит на новые сутки.
            val zone = ZoneId.systemDefault()
            val startDay = today.minusDays(range.days - 1L)
            val start = startDay.toEpochDay()
            val end = today.toEpochDay()
            combine(
                usageDao.observeLogsInRange(start, end),
                blockedAttemptDao.observeAttemptsInRange(start, end),
                habitDao.observeHabitsWithLogs(start, end),
            ) { usageLogs, attempts, habits ->
                AnalyticsAggregator.aggregate(
                    range = range,
                    startDay = startDay,
                    today = today,
                    currentHour = LocalTime.now(zone).hour,
                    zone = zone,
                    usageLogs = usageLogs,
                    attempts = attempts,
                    habits = habits,
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AnalyticsUiState.Loading,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val nightState: StateFlow<NightStatsUiState?> = combine(
        _selectedRange,
        BedtimeRepository.configFlow(application),
    ) { range, config -> range to config.effectiveAt(System.currentTimeMillis()) }
        .distinctUntilChanged()
        .flatMapLatest { (range, settings) ->
            if (!settings.enabled || !settings.hasWindow) return@flatMapLatest flowOf(null)
            val zone = ZoneId.systemDefault()
            val nights = NightStatsCalculator.nightCount(range)
            val first = NightStatsCalculator.firstWindow(nights, settings, System.currentTimeMillis(), zone)
            combine(
                usageDao.observeLogsInRange(first.startDate.toEpochDay(), LocalDate.now(zone).toEpochDay()),
                protectionGapDao.observeOverlapping(first.startWall, Long.MAX_VALUE),
            ) { logs, gaps ->
                NightStatsCalculator.build(
                    settings = settings,
                    nights = nights,
                    logs = logs,
                    gaps = gaps,
                    nowWall = System.currentTimeMillis(),
                    zone = zone,
                    locale = Locale.getDefault(),
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    /** Detox Score за последние 7 дней; пересчитывается при любом изменении данных и смене дня. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val scoreState: StateFlow<DetoxScore?> = DayRollover.dayFlow(application)
        .flatMapLatest { today ->
            val zone = ZoneId.systemDefault()
            val first = today.minusDays(DetoxScoreCalculator.WINDOW_DAYS.toLong())
            combine(
                usageDao.observeLogsInRange(first.toEpochDay(), today.toEpochDay()),
                protectionGapDao.observeOverlapping(first.atStartOfDay(zone).toInstant().toEpochMilli(), Long.MAX_VALUE),
                habitDao.observeHabitsWithLogs(first.toEpochDay(), today.toEpochDay()),
                BedtimeRepository.configFlow(application),
            ) { logs, gaps, habits, bedtime ->
                val now = System.currentTimeMillis()
                DetoxScoreCalculator.compute(today, zone, now, bedtime.effectiveAt(now), logs, gaps, habits)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun selectRange(range: AnalyticsRange) {
        _selectedRange.value = range
    }

    /**
     * Перенос свежей системной статистики в базу; графики обновятся сами через Flow Room.
     * Без доступа к истории использования ничего не делает.
     */
    fun syncUsage() {
        viewModelScope.launch { UsageSync.sync(getApplication()) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
