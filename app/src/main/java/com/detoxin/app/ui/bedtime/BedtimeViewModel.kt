package com.detoxin.app.ui.bedtime

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.detoxin.app.bedtime.BedtimeRepository
import com.detoxin.app.bedtime.BedtimeScheduler
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.BedtimeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * @property settings настройки, действующие прямо сейчас.
 * @property pending отложенное ослабление, которое вступит в силу в [pendingFromLabel].
 * @property lockedUntilLabel не `null` — сейчас ночь (или до неё меньше 30 минут) и
 * ослабление будет отложено до этого времени.
 */
@Immutable
data class BedtimeUiState(
    val settings: BedtimeSettings,
    val pending: BedtimeSettings?,
    val pendingFromLabel: String?,
    val lockedUntilLabel: String?,
)

/**
 * Экран настроек «Отбоя». Состояние «замка» зависит от времени, поэтому пересчитывается раз в
 * [LOCK_REFRESH_MS] даже без изменений в DataStore. Сохранение и перепланирование будильников —
 * на Dispatchers.IO; итог сообщается через [messages].
 */
class BedtimeViewModel(application: Application) : AndroidViewModel(application) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(LOCK_REFRESH_MS)
        }
    }

    val state: StateFlow<BedtimeUiState?> = combine(BedtimeRepository.configFlow(application), clock) { config, now ->
        val settings = config.effectiveAt(now)
        BedtimeUiState(
            settings = settings,
            pending = config.pendingAt(now),
            pendingFromLabel = config.pendingAt(now)?.let { formatClock(config.pendingFromWall) },
            lockedUntilLabel = BedtimePolicy.lockEnd(now, zone, settings)?.let(::formatClock),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun save(requested: BedtimeSettings) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val result = BedtimeRepository.update(context, requested, now, zone)
                BedtimeScheduler.reschedule(context, result, now, zone)
                val pending = result.pendingAt(now)
                messageChannel.send(
                    if (pending != null) {
                        "Сейчас действуют более строгие настройки. Ослабление вступит в силу в ${formatClock(result.pendingFromWall)}."
                    } else {
                        "Настройки «Отбоя» сохранены"
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save bedtime settings", e)
                messageChannel.send("Не удалось сохранить настройки")
            }
        }
    }

    private fun formatClock(wall: Long): String = Instant.ofEpochMilli(wall).atZone(zone).format(CLOCK_FORMAT)

    private companion object {
        const val TAG = "BedtimeViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val LOCK_REFRESH_MS = 30_000L
        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
