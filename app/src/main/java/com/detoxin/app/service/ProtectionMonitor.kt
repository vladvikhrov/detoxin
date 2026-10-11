package com.detoxin.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.detoxin.app.bedtime.BedtimeNotifications
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.entity.ProtectionGap
import com.detoxin.app.usage.UsageStatsReader
import com.detoxin.app.util.ProcessScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.ZoneId

private val Context.protectionStore: DataStore<Preferences> by preferencesDataStore(name = "detoxin_protection")

/**
 * Учёт периодов, когда защита не работала ([ProtectionGap]).
 *
 * Выключить службу специальных возможностей Android позволяет всегда — и это правильно.
 * Задача монитора — сделать выключение видимым: после повторного включения пользователь
 * получает итог «Защита была выключена 23:47–02:10, TikTok: 1 ч 52 мин». Время берётся из
 * системной статистики, которую Android ведёт и при выключенной службе.
 *
 * Источники разрыва:
 *  - явное выключение: `onUnbind`/`onDestroy` службы открывают разрыв ([onProtectionStopping]);
 *  - процесс убит без колбэков (force stop, прошивка): при следующем подключении разрыв
 *    восстанавливается по последнему heartbeat ([heartbeat] раз в [HEARTBEAT_INTERVAL_MS]).
 *    Такой разрыв сохраняется, только если в нём действительно открывались TikTok/YouTube:
 *    иначе это сон устройства или перезагрузка, а не обход защиты.
 */
object ProtectionMonitor {

    const val HEARTBEAT_INTERVAL_MS = 60_000L

    private const val TAG = "ProtectionMonitor"
    private val KEY_HEARTBEAT = longPreferencesKey("last_heartbeat_wall")

    /** Heartbeat старше этого означает, что процесс не жил (с запасом на задержки планировщика). */
    private const val STALE_HEARTBEAT_MS = 3L * HEARTBEAT_INTERVAL_MS

    /** Короче — переподключение службы системой, а не выключение. */
    private const val MIN_STORED_GAP_MS = 60_000L

    /** Короче — не беспокоим уведомлением. */
    private const val MIN_REPORTED_GAP_MS = 5L * 60_000L

    suspend fun heartbeat(context: Context, nowWall: Long) {
        try {
            context.applicationContext.protectionStore.edit { it[KEY_HEARTBEAT] = nowWall }
        } catch (e: IOException) {
            Log.w(TAG, "Heartbeat write failed", e)
        }
    }

    /**
     * Служба останавливается (`onUnbind`/`onDestroy`). Запись идёт в [ProcessScope]: scope службы
     * в этот момент уже отменяется, а процесс обычно живёт ещё несколько секунд.
     */
    fun onProtectionStopping(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        ProcessScope.io.launch {
            try {
                DatabaseProvider.getDatabase(appContext).protectionGapDao().openIfNone(now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unable to record protection gap start", e)
            }
        }
    }

    /**
     * Служба подключилась: закрывает открытый разрыв или восстанавливает разрыв по heartbeat,
     * затем показывает итог. Вызывать до первого [heartbeat], на IO.
     */
    suspend fun onProtectionStarted(context: Context, nowWall: Long, zone: ZoneId) {
        val appContext = context.applicationContext
        val dao = DatabaseProvider.getDatabase(appContext).protectionGapDao()
        val lastHeartbeat = try {
            appContext.protectionStore.data.first()[KEY_HEARTBEAT]
        } catch (e: IOException) {
            Log.w(TAG, "Heartbeat read failed", e)
            null
        }

        val open = dao.getOpenGap()
        val gap: ProtectionGap = when {
            open != null -> {
                if (nowWall - open.startedAtWall < MIN_STORED_GAP_MS) {
                    dao.delete(open.id)
                    return
                }
                dao.close(open.id, nowWall)
                open.copy(endedAtWall = nowWall)
            }
            lastHeartbeat != null && nowWall - lastHeartbeat > STALE_HEARTBEAT_MS -> {
                // Если устройство перезагружалось, раньше загрузки защита работать не могла.
                val bootWall = nowWall - SystemClock.elapsedRealtime()
                val start = maxOf(lastHeartbeat, bootWall)
                if (nowWall - start < MIN_STORED_GAP_MS) return
                val usage = trackedUsage(appContext, start, nowWall)
                if (usage.values.sum() <= 0L) return
                val inferred = ProtectionGap(startedAtWall = start, endedAtWall = nowWall)
                dao.insert(inferred)
                inferred
            }
            else -> return
        }

        val end = gap.endedAtWall ?: nowWall
        if (end - gap.startedAtWall < MIN_REPORTED_GAP_MS) return
        BedtimeNotifications.showGapReport(
            context = appContext,
            startedAtWall = gap.startedAtWall,
            endedAtWall = end,
            usage = trackedUsage(appContext, gap.startedAtWall, end),
            zone = zone,
        )
    }

    /** Время TikTok и YouTube за отрезок; без доступа к статистике — пусто. Binder-запрос. */
    private fun trackedUsage(context: Context, fromWall: Long, toWall: Long): Map<TrackedApp, Long> {
        if (!UsageStatsReader.hasAccess(context)) return emptyMap()
        val totals = LinkedHashMap<TrackedApp, Long>()
        TrackedApp.entries.forEach { totals[it] = 0L }
        for (session in UsageStatsReader.readSessions(context, fromWall, toWall, TrackedApp.packageNames)) {
            val app = TrackedApp.fromPackage(session.packageName) ?: continue
            totals.merge(app, session.endMs - session.startMs, Long::plus)
        }
        return totals
    }
}
