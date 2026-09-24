package com.detoxin.app.ui.analytics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.detoxin.app.data.local.DatabaseProvider
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * Единая точка данных экрана аналитики.
 *
 * Смена диапазона через [flatMapLatest] отменяет подписки на старые запросы Room и
 * открывает новые. Room выполняет запросы на своём executor-е, [combine] и агрегация
 * идут на [Dispatchers.Default]. Промежуточный Loading при смене диапазона не выдаётся:
 * экран держит старые данные, пока не придут новые, и графики плавно морфят значения.
 */
class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = DatabaseProvider.getDatabase(application)
    private val usageDao = database.usageDao()
    private val blockedAttemptDao = database.blockedAttemptDao()
    private val habitDao = database.habitDao()

    private val _selectedRange = MutableStateFlow(AnalyticsRange.WEEK)
    val selectedRange: StateFlow<AnalyticsRange> = _selectedRange.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<AnalyticsUiState> = _selectedRange
        .flatMapLatest { range ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startDay = today.minusDays(range.days - 1L)
            val start = startDay.toEpochDay()
            val end = today.toEpochDay()
            combine(
                usageDao.observeLogsInRange(start, end),
                blockedAttemptDao.observeAttemptsInRange(start, end),
                habitDao.observeHabitsWithLogs(start, end),
            ) { usageLogs, attempts, habits ->
                AnalyticsAggregator.aggregate(range, startDay, today, zone, usageLogs, attempts, habits)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AnalyticsUiState.Loading,
        )

    fun selectRange(range: AnalyticsRange) {
        _selectedRange.value = range
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
