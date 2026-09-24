package com.detoxin.app.service

import android.content.Context
import android.os.SystemClock
import androidx.annotation.MainThread
import com.detoxin.app.R
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.overlay.OverlayContent
import com.detoxin.app.overlay.OverlayManager
import com.detoxin.app.usage.LimitBlock
import com.detoxin.app.usage.UsageBudget
import com.detoxin.app.usage.UsageSnapshot
import com.detoxin.app.usage.UsageStatsReader
import com.detoxin.app.usage.UsageSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Лимиты экранного времени [TrackedApp] по системной статистике Android.
 *
 * Источник времени — `UsageStatsManager` (через [UsageBudget]), а не собственный подсчёт службы:
 * система ведёт статистику всегда, поэтому время не теряется, если прошивка останавливает службу.
 * Служба нужна только для быстрой реакции и показа экрана блокировки.
 *
 * Модель потоков: состояние меняется только в главном потоке. Снимок статистики (Binder-запрос)
 * берётся на [Dispatchers.Default] не чаще раза в [SNAPSHOT_INTERVAL_MS]; между снимками время
 * открытого приложения досчитывается по `elapsedRealtime`. Синхронизация в базу — [UsageSync] на IO.
 *
 * Лимиты: дневной (до полуночи) и часовой (до начала следующего часа). Экран блокировки показан,
 * пока заблокированное приложение на экране, и снимается при уходе из него.
 */
class AppUsageTracker(
    private val context: Context,
    private val overlayManager: OverlayManager,
    private val tickerScope: CoroutineScope,
    private val foregroundProbe: () -> String?,
    private val onGoHome: () -> Unit,
) {

    /** Отдельный scope синхронизации: финальная запись при остановке службы не отменяется. */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var lastForeground: TrackedApp? = null
    private var snapshot: UsageSnapshot? = null
    private var snapshotJob: Job? = null
    private var syncJob: Job? = null
    private var lastSyncElapsed: Long = Long.MIN_VALUE / 2
    private var accessCheckedElapsed: Long = Long.MIN_VALUE / 2
    private var hasUsageAccess: Boolean = false
    private var tickerJob: Job? = null

    /** Событие окна отслеживаемого приложения: оно сейчас на экране. */
    @MainThread
    fun onTrackedAppEvent(packageName: String) {
        val app = TrackedApp.fromPackage(packageName) ?: return
        evaluate(app)
        ensureTicker()
    }

    /** Остановка службы: дописываем статистику в базу. */
    @MainThread
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        syncScope.launch { UsageSync.sync(context) }
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = tickerScope.launch(Dispatchers.Main.immediate) {
            while (isActive && (lastForeground != null || overlayManager.isShowing)) {
                delay(TICK_MS)
                val foregroundPackage = withContext(Dispatchers.Default) { foregroundProbe() }
                evaluate(TrackedApp.fromPackage(foregroundPackage))
            }
        }
    }

    private fun evaluate(foreground: TrackedApp?) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()

        val leftTrackedApp = lastForeground != null && lastForeground != foreground
        lastForeground = foreground
        if (leftTrackedApp) requestSync(nowElapsed)

        if (foreground == null || !usageAccess(nowElapsed)) {
            overlayManager.hide()
            return
        }

        requestSnapshotIfStale(nowElapsed)
        if (nowElapsed - lastSyncElapsed >= SYNC_INTERVAL_MS) requestSync(nowElapsed)

        val current = snapshot ?: return
        val (dayUsed, hourUsed) = usedNow(current, foreground, nowWall, nowElapsed)
        val block = UsageBudget.decide(foreground, dayUsed, hourUsed, nowWall, zone)
        if (block == null || block.untilWall <= nowWall) {
            if (overlayManager.isShowing) overlayManager.hide()
            return
        }
        showBlock(foreground, block, nowWall, nowElapsed)
    }

    /**
     * Использовано сегодня и в текущем часу: значения снимка + время с момента снимка, если
     * приложение было открыто. После смены часа/дня старые значения не переносятся.
     */
    private fun usedNow(current: UsageSnapshot, app: TrackedApp, nowWall: Long, nowElapsed: Long): Pair<Long, Long> {
        val extra = if (app.packageName in current.openPackages) {
            (nowElapsed - current.takenAtElapsed).coerceAtLeast(0L)
        } else {
            0L
        }
        val dayStart = UsageBudget.dayStart(nowWall, zone)
        val hourStart = UsageBudget.hourStart(nowWall, zone)
        val day = if (dayStart == current.dayStartWall) {
            (current.dayUsed[app.packageName] ?: 0L) + extra
        } else {
            minOf(extra, nowWall - dayStart)
        }
        val hour = if (hourStart == current.hourStartWall) {
            (current.hourUsed[app.packageName] ?: 0L) + extra
        } else {
            minOf(extra, nowWall - hourStart)
        }
        return day to hour
    }

    private fun showBlock(app: TrackedApp, block: LimitBlock, nowWall: Long, nowElapsed: Long) {
        val content = when (block.kind) {
            LimitBlock.Kind.DAILY -> OverlayContent(
                title = context.getString(R.string.overlay_limit_title, app.displayName, app.limitLabel),
                message = context.getString(R.string.overlay_limit_message),
                actionLabel = context.getString(R.string.overlay_go_home),
            )
            LimitBlock.Kind.HOURLY -> OverlayContent(
                title = context.getString(
                    R.string.overlay_hourly_title,
                    app.displayName,
                    app.hourlyLimitLabel.orEmpty(),
                ),
                message = context.getString(
                    R.string.overlay_hourly_message,
                    Instant.ofEpochMilli(block.untilWall).atZone(zone).format(HOUR_FORMAT),
                ),
                actionLabel = context.getString(R.string.overlay_go_home),
            )
        }
        if (overlayManager.isShowing && overlayManager.currentContent == content) return
        overlayManager.show(content, nowElapsed + (block.untilWall - nowWall), onAction = onGoHome)
    }

    private fun requestSnapshotIfStale(nowElapsed: Long) {
        if (snapshotJob?.isActive == true) return
        val current = snapshot
        if (current != null && nowElapsed - current.takenAtElapsed < SNAPSHOT_INTERVAL_MS) return
        snapshotJob = tickerScope.launch(Dispatchers.Default) {
            val fresh = UsageBudget.snapshot(
                context = context,
                nowWall = System.currentTimeMillis(),
                nowElapsed = SystemClock.elapsedRealtime(),
                zone = zone,
            )
            withContext(Dispatchers.Main.immediate) { snapshot = fresh }
        }
    }

    private fun requestSync(nowElapsed: Long) {
        if (syncJob?.isActive == true) return
        lastSyncElapsed = nowElapsed
        syncJob = syncScope.launch { UsageSync.sync(context) }
    }

    private fun usageAccess(nowElapsed: Long): Boolean {
        if (nowElapsed - accessCheckedElapsed >= ACCESS_RECHECK_MS) {
            hasUsageAccess = UsageStatsReader.hasAccess(context)
            accessCheckedElapsed = nowElapsed
        }
        return hasUsageAccess
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val SNAPSHOT_INTERVAL_MS = 5_000L
        const val SYNC_INTERVAL_MS = 30_000L
        const val ACCESS_RECHECK_MS = 10_000L
        val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
