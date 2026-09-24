package com.detoxin.app.usage

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/** Непрерывный отрезок, когда приложение было на экране. [isOpen] — всё ещё открыто на момент чтения. */
data class AppSession(
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
    val isOpen: Boolean,
)

/**
 * Чтение системной статистики использования (`UsageStatsManager`) — тех же данных, что показывает
 * «Цифровое благополучие». Система ведёт их всегда, поэтому экранное время не теряется, даже если
 * служба специальных возможностей выключена или убита прошивкой.
 *
 * Требует специального доступа «Доступ к истории использования» (выдаёт пользователь).
 * Работает полностью офлайн: данные читаются из системы и не покидают устройство.
 */
object UsageStatsReader {

    /**
     * Запас назад от начала окна: чтобы увидеть событие открытия приложения, если оно
     * произошло раньше окна, а сессия продолжается внутри него.
     */
    private const val LOOKBACK_MS = 3L * 60L * 60L * 1_000L

    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * Сессии [packages] в окне `[fromMs, toMs]`, обрезанные по границам окна.
     *
     * Приложение считается открытым, пока открыт хотя бы один его экран (Activity):
     * `ACTIVITY_RESUMED` добавляет экран, `ACTIVITY_PAUSED`/`ACTIVITY_STOPPED` убирает именно его.
     * Это важно: при переходе между экранами одного приложения `STOPPED` старого экрана приходит
     * уже после `RESUMED` нового, и закрывать сессию по нему нельзя. Выключение экрана, блокировка
     * и выключение устройства закрывают все сессии.
     * Выполняет Binder-запрос: вызывать вне главного потока.
     */
    fun readSessions(context: Context, fromMs: Long, toMs: Long, packages: Set<String>): List<AppSession> {
        if (toMs <= fromMs) return emptyList()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = manager.queryEvents(fromMs - LOOKBACK_MS, toMs) ?: return emptyList()

        val openSince = HashMap<String, Long>()
        val openActivities = HashMap<String, MutableSet<String>>()
        val raw = ArrayList<AppSession>()
        val event = UsageEvents.Event()

        fun close(packageName: String, at: Long) {
            val start = openSince.remove(packageName) ?: return
            if (at > start) raw += AppSession(packageName, start, at, isOpen = false)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val time = event.timeStamp
            val packageName = event.packageName
            val activity = event.className.orEmpty()
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (packageName in packages) {
                    val activities = openActivities.getOrPut(packageName) { HashSet() }
                    if (activities.isEmpty()) openSince[packageName] = time
                    activities += activity
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (packageName in packages) {
                    val activities = openActivities[packageName]
                    activities?.remove(activity)
                    if (activities.isNullOrEmpty()) close(packageName, time)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    openSince.keys.toList().forEach { close(it, time) }
                    openActivities.clear()
                }
            }
        }
        openSince.forEach { (packageName, start) ->
            if (toMs > start) raw += AppSession(packageName, start, toMs, isOpen = true)
        }

        return raw.mapNotNull { session ->
            val start = maxOf(session.startMs, fromMs)
            val end = minOf(session.endMs, toMs)
            if (end > start) session.copy(startMs = start, endMs = end) else null
        }
    }
}
