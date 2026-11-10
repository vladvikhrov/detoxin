package com.detoxin.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.detoxin.app.ui.widget.HabitGlanceWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Смена календарного дня — единая точка для экранов и виджетов.
 *
 * День всегда берётся в часовом поясе устройства (`ZoneId.systemDefault()`), а не в UTC:
 * отметки привычек хранятся как локальный `epochDay`.
 *
 * Зачем нужен отдельный механизм. Раньше виджеты переходили на новый день только по неточному
 * non-wakeup будильнику на 00:00:01. Он не будит устройство, а после пробуждения доставляется
 * не сразу — и за это время любая отрисовка виджета успевала перепланировать его на СЛЕДУЮЩУЮ
 * полночь, затирая просроченный. В итоге вчерашняя галочка могла висеть сутками.
 * Теперь последний отрисованный день хранится локально, и каждый удобный момент (включение
 * экрана, синхронизация статистики, открытие приложения, периодическое обновление виджета)
 * вызывает [ensureCurrent]: если день сменился, виджеты перерисовываются.
 */
object DayRollover {

    private const val TAG = "DayRollover"
    private const val PREFS = "detoxin_day_rollover"
    private const val KEY_RENDERED_DAY = "rendered_epoch_day"

    private val mutex = Mutex()

    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    /** Перерисовывает виджеты, если с последней отрисовки сменился локальный день. Дёшево, если нет. */
    suspend fun ensureCurrent(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        mutex.withLock {
            val today = today().toEpochDay()
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_RENDERED_DAY, Long.MIN_VALUE) == today) return@withLock
            try {
                HabitGlanceWidget.refreshAllForNewDay(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Day rollover refresh failed", e)
            }
        }
    }

    /** Виджеты отрисованы на день [epochDay] (вызывает сама перерисовка). */
    fun markRendered(context: Context, epochDay: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_RENDERED_DAY, epochDay).apply()
    }

    /**
     * Текущая локальная дата, которая обновляется сама: в полночь, при ручной смене времени и
     * при смене часового пояса — без перезапуска приложения.
     *
     * Источник — системные рассылки, а не `delay` до полуночи: таймер корутин не идёт, пока
     * устройство спит, и мог опоздать на часы. `TIME_TICK` приходит в начале каждой минуты,
     * пока экран включён, поэтому полночь ловится в 00:00:00; `distinctUntilChanged` оставляет
     * от минутных тиков только смену даты.
     */
    fun dayFlow(context: Context): Flow<LocalDate> = callbackFlow {
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(today())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(today())
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.conflate().distinctUntilChanged()
}
