package com.detoxin.app.ui.protection

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.detoxin.app.MainActivity
import com.detoxin.app.core.HardBlockedApp
import com.detoxin.app.data.local.backup.BackupResult
import com.detoxin.app.data.local.backup.DatabaseBackup
import com.detoxin.app.guard.BootClock
import com.detoxin.app.guard.GuardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

@Immutable
data class StrictAppUi(val app: HardBlockedApp, val strict: Boolean)

/**
 * @property emergencyRemainingMs сколько ещё длится экстренная разблокировка; `0` — не активна.
 */
@Immutable
data class ProtectionUiState(
    val pinSet: Boolean,
    val apps: List<StrictAppUi>,
    val emergencyRemainingMs: Long,
)

/**
 * Экран «Защита»: мастер-PIN, полная блокировка приложений, экстренная разблокировка и
 * резервная копия базы. Проверки PIN и фразы выполняет экран (GuardDialog); сюда приходят уже
 * подтверждённые действия. Записи — на Dispatchers.IO.
 */
class ProtectionViewModel(application: Application) : AndroidViewModel(application) {

    private val bootCount = BootClock.bootCount(application)

    /** Раз в секунду, пока идёт экстренная разблокировка, — для обратного отсчёта. */
    private val elapsedTicker: Flow<Long> = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<ProtectionUiState?> = combine(GuardRepository.flow(application), elapsedTicker) { guard, now ->
        ProtectionUiState(
            pinSet = guard.pinSet,
            apps = HardBlockedApp.entries.map { StrictAppUi(it, guard.isStrict(it)) },
            emergencyRemainingMs = guard.emergencyRemainingMs(now, bootCount),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val _busy = MutableStateFlow(false)

    /** Идёт экспорт или импорт: кнопки резервной копии недоступны. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun setPin(pin: String) = launchIo("PIN сохранён") { GuardRepository.setPin(getApplication(), pin) }

    fun setStrict(app: HardBlockedApp, strict: Boolean) = launchIo(
        if (strict) "${app.displayName}: полная блокировка включена" else "${app.displayName}: полная блокировка снята",
    ) { GuardRepository.setStrict(getApplication(), app, strict) }

    fun startEmergency() = launchIo("Блокировки приостановлены на 15 минут. Период записан в срывы.") {
        GuardRepository.startEmergency(getApplication(), System.currentTimeMillis())
    }

    fun stopEmergency() = launchIo("Защита снова включена") {
        GuardRepository.stopEmergency(getApplication(), System.currentTimeMillis())
    }

    fun exportTo(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = DatabaseBackup.export(getApplication(), uri)
            messageChannel.send(
                when (result) {
                    is BackupResult.Success -> result.message
                    is BackupResult.Failure -> result.message
                }
            )
            _busy.value = false
        }
    }

    /**
     * Импорт подменяет файл базы под закрытыми соединениями, поэтому после успеха процесс
     * перезапускается: все ранее выданные DAO привязаны к закрытому экземпляру Room.
     * Службу специальных возможностей система подключит заново сама.
     */
    fun importFrom(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            when (val result = DatabaseBackup.import(getApplication(), uri)) {
                is BackupResult.Success -> {
                    messageChannel.send(result.message + ". Перезапуск…")
                    delay(RESTART_DELAY_MS)
                    restartProcess()
                }
                is BackupResult.Failure -> {
                    messageChannel.send(result.message)
                    _busy.value = false
                }
            }
        }
    }

    private fun restartProcess() {
        val application = getApplication<Application>()
        val intent = Intent.makeRestartActivityTask(ComponentName(application, MainActivity::class.java))
        application.startActivity(intent)
        exitProcess(0)
    }

    private fun launchIo(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                messageChannel.send(successMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Protection change failed", e)
                messageChannel.send("Не удалось сохранить изменение")
            }
        }
    }

    private companion object {
        const val TAG = "ProtectionViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 1_000L
        const val RESTART_DELAY_MS = 1_200L
    }
}
