package com.detoxin.app.bedtime

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.detoxin.app.usage.BedtimeConfig
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.BedtimeSettings
import com.detoxin.app.util.ProcessScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import java.time.ZoneId

private val Context.bedtimeStore: DataStore<Preferences> by preferencesDataStore(name = "detoxin_bedtime")

/**
 * Настройки «Отбоя» в DataStore Preferences (не в Room: это конфигурация, а не история).
 *
 * Служба читает их на главном потоке при каждой проверке лимита, поэтому для неё есть
 * горячий [state] — StateFlow уровня процесса, без дискового чтения на горячем пути.
 * Все изменения проходят через [update], где применяется правило «ночью только строже»
 * ([BedtimePolicy.applyChange]).
 */
object BedtimeRepository {

    private const val TAG = "BedtimeRepository"

    private val KEY_ENABLED = booleanPreferencesKey("enabled")
    private val KEY_START = intPreferencesKey("start_minute")
    private val KEY_WAKE = intPreferencesKey("wake_minute")
    private val KEY_ALLOWANCE = intPreferencesKey("allowance_minutes")
    private val KEY_PENDING_ENABLED = booleanPreferencesKey("pending_enabled")
    private val KEY_PENDING_START = intPreferencesKey("pending_start_minute")
    private val KEY_PENDING_WAKE = intPreferencesKey("pending_wake_minute")
    private val KEY_PENDING_ALLOWANCE = intPreferencesKey("pending_allowance_minutes")
    private val KEY_PENDING_FROM = longPreferencesKey("pending_from_wall")

    @Volatile
    private var shared: StateFlow<BedtimeConfig>? = null

    fun configFlow(context: Context): Flow<BedtimeConfig> =
        context.applicationContext.bedtimeStore.data
            .catch { error ->
                if (error is IOException) {
                    Log.e(TAG, "Bedtime settings are unreadable, using defaults", error)
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map(::read)
            .distinctUntilChanged()

    /**
     * Горячая конфигурация для службы. До первого чтения с диска — [BedtimeConfig.DEFAULT]
     * (режим выключен): первые миллисекунды после старта процесса ограничиваются обычными лимитами.
     */
    fun state(context: Context): StateFlow<BedtimeConfig> =
        shared ?: synchronized(this) {
            shared ?: configFlow(context)
                .stateIn(ProcessScope.io, SharingStarted.Eagerly, BedtimeConfig.DEFAULT)
                .also { shared = it }
        }

    suspend fun current(context: Context): BedtimeConfig = configFlow(context).first()

    /** Сохраняет запрошенные настройки с учётом «замка» и возвращает итоговую конфигурацию. */
    suspend fun update(context: Context, requested: BedtimeSettings, nowWall: Long, zone: ZoneId): BedtimeConfig {
        var result = BedtimeConfig.DEFAULT
        context.applicationContext.bedtimeStore.edit { prefs ->
            val next = BedtimePolicy.applyChange(read(prefs), sanitize(requested), nowWall, zone)
            write(prefs, next)
            result = next
        }
        return result
    }

    /** Утром отложенное ослабление становится обычной конфигурацией. */
    suspend fun promotePending(context: Context, nowWall: Long) {
        context.applicationContext.bedtimeStore.edit { prefs ->
            val config = read(prefs)
            val pending = config.pending ?: return@edit
            if (nowWall >= config.pendingFromWall) {
                write(prefs, BedtimeConfig(active = pending, pending = null, pendingFromWall = 0L))
            }
        }
    }

    private fun sanitize(settings: BedtimeSettings): BedtimeSettings = settings.copy(
        startMinute = settings.startMinute.coerceIn(0, BedtimeSettings.MINUTES_PER_DAY - 1),
        wakeMinute = settings.wakeMinute.coerceIn(0, BedtimeSettings.MINUTES_PER_DAY - 1),
        nightAllowanceMinutes = settings.nightAllowanceMinutes.coerceIn(0, BedtimeSettings.MAX_ALLOWANCE_MINUTES),
    )

    private fun read(prefs: Preferences): BedtimeConfig {
        val defaults = BedtimeSettings.DEFAULT
        val active = BedtimeSettings(
            enabled = prefs[KEY_ENABLED] ?: defaults.enabled,
            startMinute = prefs[KEY_START] ?: defaults.startMinute,
            wakeMinute = prefs[KEY_WAKE] ?: defaults.wakeMinute,
            nightAllowanceMinutes = prefs[KEY_ALLOWANCE] ?: defaults.nightAllowanceMinutes,
        )
        val pendingFrom = prefs[KEY_PENDING_FROM]
        val pending = if (pendingFrom == null) {
            null
        } else {
            BedtimeSettings(
                enabled = prefs[KEY_PENDING_ENABLED] ?: active.enabled,
                startMinute = prefs[KEY_PENDING_START] ?: active.startMinute,
                wakeMinute = prefs[KEY_PENDING_WAKE] ?: active.wakeMinute,
                nightAllowanceMinutes = prefs[KEY_PENDING_ALLOWANCE] ?: active.nightAllowanceMinutes,
            )
        }
        return BedtimeConfig(active = active, pending = pending, pendingFromWall = pendingFrom ?: 0L)
    }

    private fun write(prefs: MutablePreferences, config: BedtimeConfig) {
        prefs[KEY_ENABLED] = config.active.enabled
        prefs[KEY_START] = config.active.startMinute
        prefs[KEY_WAKE] = config.active.wakeMinute
        prefs[KEY_ALLOWANCE] = config.active.nightAllowanceMinutes
        val pending = config.pending
        if (pending == null) {
            prefs.remove(KEY_PENDING_ENABLED)
            prefs.remove(KEY_PENDING_START)
            prefs.remove(KEY_PENDING_WAKE)
            prefs.remove(KEY_PENDING_ALLOWANCE)
            prefs.remove(KEY_PENDING_FROM)
        } else {
            prefs[KEY_PENDING_ENABLED] = pending.enabled
            prefs[KEY_PENDING_START] = pending.startMinute
            prefs[KEY_PENDING_WAKE] = pending.wakeMinute
            prefs[KEY_PENDING_ALLOWANCE] = pending.nightAllowanceMinutes
            prefs[KEY_PENDING_FROM] = config.pendingFromWall
        }
    }
}
