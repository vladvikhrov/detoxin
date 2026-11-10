package com.detoxin.app.ui.relapse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.usage.UsageSync
import com.detoxin.app.util.DayRollover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/**
 * Данные «Стены стыда» за последние [WINDOW_DAYS] дней: разрывы защиты и сессии приложений из
 * Room. Открытый разрыв растёт со временем, поэтому состояние пересчитывается раз в минуту.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelapseViewModel(application: Application) : AndroidViewModel(application) {

    private val database = DatabaseProvider.getDatabase(application)

    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<RelapseUiState?> = DayRollover.dayFlow(application)
        .flatMapLatest { today ->
            val zone = ZoneId.systemDefault()
            val firstDay = today.minusDays(WINDOW_DAYS - 1L)
            val windowStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
            combine(
                database.protectionGapDao().observeOverlapping(windowStart, Long.MAX_VALUE),
                database.usageDao().observeLogsInRange(firstDay.toEpochDay() - 1L, today.toEpochDay()),
                minuteTicker,
            ) { gaps, logs, now ->
                RelapseCalculator.build(
                    gaps = gaps,
                    logs = logs,
                    nowWall = now,
                    today = today,
                    zone = zone,
                    days = WINDOW_DAYS,
                    locale = Locale.getDefault(),
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Свежая системная статистика: время «в обход» считается по синхронизированным сессиям. */
    fun syncUsage() {
        viewModelScope.launch { UsageSync.sync(getApplication()) }
    }

    private companion object {
        const val WINDOW_DAYS = 14
        const val TICK_MS = 60_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
