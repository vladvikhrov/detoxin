package com.detoxin.app.guard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.detoxin.app.core.HardBlockedApp
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.entity.ProtectionGap
import com.detoxin.app.data.local.entity.ProtectionGapReason
import com.detoxin.app.util.ProcessScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.IOException

private val Context.guardStore: DataStore<Preferences> by preferencesDataStore(name = "detoxin_guard")

/**
 * Состояние защиты от самосаботажа.
 *
 * @property relaxedApps ключи [HardBlockedApp], у которых полная блокировка выключена.
 * @property emergencyUntilElapsed конец экстренной разблокировки по `elapsedRealtime` той
 * загрузки устройства, номер которой записан в [emergencyBootCount]. Монотонные часы выбраны
 * намеренно: перевод системного времени не продлевает разблокировку, а перезагрузка её завершает.
 */
data class GuardState(
    val pinSet: Boolean,
    val relaxedApps: Set<String>,
    val emergencyUntilElapsed: Long,
    val emergencyBootCount: Int,
) {
    fun isStrict(app: HardBlockedApp): Boolean = app.key !in relaxedApps

    fun emergencyRemainingMs(nowElapsed: Long, bootCount: Int): Long =
        if (bootCount == emergencyBootCount) (emergencyUntilElapsed - nowElapsed).coerceAtLeast(0L) else 0L

    fun emergencyActive(nowElapsed: Long, bootCount: Int): Boolean = emergencyRemainingMs(nowElapsed, bootCount) > 0L

    companion object {
        /** До первого чтения с диска действуют самые строгие настройки. */
        val DEFAULT = GuardState(pinSet = false, relaxedApps = emptySet(), emergencyUntilElapsed = 0L, emergencyBootCount = -1)
    }
}

sealed interface PinCheck {
    data object Ok : PinCheck
    data class Wrong(val attemptsLeft: Int) : PinCheck
    data class LockedOut(val remainingMs: Long) : PinCheck
}

/** Номер текущей загрузки устройства: вместе с `elapsedRealtime` образует монотонные часы. */
object BootClock {
    @Volatile
    private var cached: Int? = null

    fun bootCount(context: Context): Int = cached ?: Settings.Global
        .getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT, 0)
        .also { cached = it }
}

/**
 * Хранилище защиты от самосаботажа в DataStore Preferences: хэш мастер-PIN, переключатели
 * полной блокировки и экстренная разблокировка. Служба читает горячий [state] без диска.
 *
 * PIN хранится только как PBKDF2-хэш с солью ([PinHasher]). После [MAX_ATTEMPTS] неверных
 * попыток ввод блокируется на [LOCKOUT_MS]; срок считается по монотонным часам, поэтому перевод
 * системного времени его не сокращает (после перезагрузки отсчёт начинается заново).
 */
object GuardRepository {

    const val EMERGENCY_DURATION_MS = 15L * 60L * 1_000L
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 5L * 60L * 1_000L

    private const val TAG = "GuardRepository"

    private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
    private val KEY_PIN_SALT = stringPreferencesKey("pin_salt")
    private val KEY_PIN_ITERATIONS = intPreferencesKey("pin_iterations")
    private val KEY_FAILED = intPreferencesKey("pin_failed_attempts")
    private val KEY_LOCKOUT_ELAPSED = longPreferencesKey("pin_lockout_until_elapsed")
    private val KEY_LOCKOUT_BOOT = intPreferencesKey("pin_lockout_boot")
    private val KEY_EMERGENCY_ELAPSED = longPreferencesKey("emergency_until_elapsed")
    private val KEY_EMERGENCY_BOOT = intPreferencesKey("emergency_boot")

    private fun relaxedKey(app: HardBlockedApp) = booleanPreferencesKey("relaxed_${app.key}")

    @Volatile
    private var shared: StateFlow<GuardState>? = null

    fun flow(context: Context): Flow<GuardState> =
        context.applicationContext.guardStore.data
            .catch { error ->
                if (error is IOException) {
                    Log.e(TAG, "Guard settings are unreadable, using strict defaults", error)
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map(::read)
            .distinctUntilChanged()

    fun state(context: Context): StateFlow<GuardState> =
        shared ?: synchronized(this) {
            shared ?: flow(context)
                .stateIn(ProcessScope.io, SharingStarted.Eagerly, GuardState.DEFAULT)
                .also { shared = it }
        }

    suspend fun current(context: Context): GuardState = flow(context).first()

    /** Задаёт или меняет PIN. Проверку старого PIN выполняет вызывающий экран. */
    suspend fun setPin(context: Context, pin: String) {
        require(PinHasher.isValidPin(pin)) { "PIN must be ${PinHasher.MIN_LENGTH}-${PinHasher.MAX_LENGTH} digits" }
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt) }
        context.applicationContext.guardStore.edit { prefs ->
            prefs[KEY_PIN_HASH] = PinHasher.toHex(hash)
            prefs[KEY_PIN_SALT] = PinHasher.toHex(salt)
            prefs[KEY_PIN_ITERATIONS] = PinHasher.ITERATIONS
            prefs.remove(KEY_FAILED)
            prefs.remove(KEY_LOCKOUT_ELAPSED)
            prefs.remove(KEY_LOCKOUT_BOOT)
        }
    }

    suspend fun verifyPin(context: Context, pin: String): PinCheck {
        val appContext = context.applicationContext
        val prefs = appContext.guardStore.data.first()
        val hash = prefs[KEY_PIN_HASH]?.let(PinHasher::fromHex)
        val salt = prefs[KEY_PIN_SALT]?.let(PinHasher::fromHex)
        if (hash == null || salt == null) return PinCheck.Ok

        val boot = BootClock.bootCount(appContext)
        val nowElapsed = SystemClock.elapsedRealtime()
        val lockedUntil = prefs[KEY_LOCKOUT_ELAPSED] ?: 0L
        if (prefs[KEY_LOCKOUT_BOOT] == boot && nowElapsed < lockedUntil) {
            return PinCheck.LockedOut(lockedUntil - nowElapsed)
        }

        val iterations = prefs[KEY_PIN_ITERATIONS] ?: PinHasher.ITERATIONS
        val matches = withContext(Dispatchers.Default) { PinHasher.verify(pin, salt, iterations, hash) }
        var result: PinCheck = PinCheck.Ok
        appContext.guardStore.edit { edit ->
            if (matches) {
                edit.remove(KEY_FAILED)
                edit.remove(KEY_LOCKOUT_ELAPSED)
                edit.remove(KEY_LOCKOUT_BOOT)
            } else {
                val failed = (edit[KEY_FAILED] ?: 0) + 1
                if (failed >= MAX_ATTEMPTS) {
                    edit.remove(KEY_FAILED)
                    edit[KEY_LOCKOUT_ELAPSED] = nowElapsed + LOCKOUT_MS
                    edit[KEY_LOCKOUT_BOOT] = boot
                    result = PinCheck.LockedOut(LOCKOUT_MS)
                } else {
                    edit[KEY_FAILED] = failed
                    result = PinCheck.Wrong(MAX_ATTEMPTS - failed)
                }
            }
        }
        return result
    }

    /** Включение блокировки — ужесточение и не требует подтверждения; выключение экран защищает сам. */
    suspend fun setStrict(context: Context, app: HardBlockedApp, strict: Boolean) {
        context.applicationContext.guardStore.edit { prefs ->
            if (strict) prefs.remove(relaxedKey(app)) else prefs[relaxedKey(app)] = true
        }
    }

    /**
     * Экстренная разблокировка на [EMERGENCY_DURATION_MS]: все блокировки и лимиты на паузе.
     * Период сразу записывается как разрыв защиты и попадает на «Стену стыда».
     */
    suspend fun startEmergency(context: Context, nowWall: Long) {
        val appContext = context.applicationContext
        appContext.guardStore.edit { prefs ->
            prefs[KEY_EMERGENCY_ELAPSED] = SystemClock.elapsedRealtime() + EMERGENCY_DURATION_MS
            prefs[KEY_EMERGENCY_BOOT] = BootClock.bootCount(appContext)
        }
        DatabaseProvider.getDatabase(appContext).protectionGapDao().insert(
            ProtectionGap(
                startedAtWall = nowWall,
                endedAtWall = nowWall + EMERGENCY_DURATION_MS,
                reason = ProtectionGapReason.EMERGENCY.key,
            )
        )
    }

    /** Досрочное завершение экстренной разблокировки; запись о разрыве укорачивается до «сейчас». */
    suspend fun stopEmergency(context: Context, nowWall: Long) {
        val appContext = context.applicationContext
        appContext.guardStore.edit { prefs ->
            prefs.remove(KEY_EMERGENCY_ELAPSED)
            prefs.remove(KEY_EMERGENCY_BOOT)
        }
        DatabaseProvider.getDatabase(appContext).protectionGapDao()
            .truncateRunning(ProtectionGapReason.EMERGENCY.key, nowWall)
    }

    private fun read(prefs: Preferences): GuardState = GuardState(
        pinSet = prefs[KEY_PIN_HASH] != null && prefs[KEY_PIN_SALT] != null,
        relaxedApps = HardBlockedApp.entries.filter { prefs[relaxedKey(it)] == true }.map { it.key }.toSet(),
        emergencyUntilElapsed = prefs[KEY_EMERGENCY_ELAPSED] ?: 0L,
        emergencyBootCount = prefs[KEY_EMERGENCY_BOOT] ?: -1,
    )
}
