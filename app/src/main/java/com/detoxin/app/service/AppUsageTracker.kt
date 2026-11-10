package com.detoxin.app.service

import android.content.Context
import android.os.SystemClock
import androidx.annotation.MainThread
import com.detoxin.app.R
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.overlay.OverlayContent
import com.detoxin.app.overlay.OverlayManager
import com.detoxin.app.overlay.OverlayStyle
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.BedtimeSettings
import com.detoxin.app.usage.BudgetUsage
import com.detoxin.app.guard.BootClock
import com.detoxin.app.usage.LimitBlock
import com.detoxin.app.usage.LimitLatchPolicy
import com.detoxin.app.usage.LimitLatchStore
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
 * Лимиты: «Отбой» (до подъёма), дневной (до полуночи, а если на неё приходится окно сна — до
 * подъёма) и часовой (до начала следующего часа). Экран блокировки показан, пока заблокированное
 * приложение на экране, и снимается при уходе из него.
 *
 * Античит: сработавший лимит «защёлкивается» по монотонным часам ([LimitLatchPolicy]), поэтому
 * перевод системного времени не возвращает свежий лимит.
 */
class AppUsageTracker(
    private val context: Context,
    private val overlayManager: OverlayManager,
    private val tickerScope: CoroutineScope,
    private val foregroundProbe: () -> String?,
    private val bedtimeProvider: () -> BedtimeSettings,
    private val pausedProvider: () -> Boolean,
    private val onGoHome: () -> Unit,
) {

    private val latchStore = LimitLatchStore(context)
    private val bootCount: Int = BootClock.bootCount(context)

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

        // Экстренная разблокировка ставит на паузу все лимиты, включая «защёлкнутые».
        if (foreground == null || pausedProvider() || !usageAccess(nowElapsed)) {
            overlayManager.hide()
            return
        }

        requestSnapshotIfStale(nowElapsed)
        if (nowElapsed - lastSyncElapsed >= SYNC_INTERVAL_MS) requestSync(nowElapsed)

        // До первого снимка статистики решение по часам неизвестно, но защёлка уже действует.
        val byClock = snapshot?.let { current ->
            val bedtime = bedtimeProvider()
            UsageBudget.decide(foreground, usedNow(current, foreground, bedtime, nowWall, nowElapsed), bedtime, nowWall, zone)
        }
        val resolution = LimitLatchPolicy.resolve(latchStore.get(foreground), byClock, nowWall, nowElapsed, bootCount)
        latchStore.put(foreground, resolution.entry)
        val block = resolution.block
        if (block == null || block.untilWall <= nowWall) {
            if (overlayManager.isShowing) overlayManager.hide()
            return
        }
        showBlock(foreground, block, nowWall, nowElapsed)
    }

    /**
     * Использовано в сутках бюджета, в текущем часу и за текущую ночь: значения снимка + время
     * с момента снимка, если приложение было открыто. Если граница (сутки, час, окно сна)
     * сменилась после снимка, старые значения не переносятся.
     */
    private fun usedNow(
        current: UsageSnapshot,
        app: TrackedApp,
        bedtime: BedtimeSettings,
        nowWall: Long,
        nowElapsed: Long,
    ): BudgetUsage {
        val extra = if (app.packageName in current.openPackages) {
            (nowElapsed - current.takenAtElapsed).coerceAtLeast(0L)
        } else {
            0L
        }
        val dayStart = BedtimePolicy.budgetDayStart(nowWall, zone, bedtime)
        val hourStart = UsageBudget.hourStart(nowWall, zone)
        val nightStart = BedtimePolicy.currentWindow(nowWall, zone, bedtime)?.startWall
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
        val night = when {
            nightStart == null -> 0L
            nightStart == current.nightStartWall -> current.nightUsedMs + extra
            else -> minOf(extra, nowWall - nightStart)
        }
        return BudgetUsage(dayUsedMs = day, hourUsedMs = hour, nightUsedMs = night)
    }

    private fun showBlock(app: TrackedApp, block: LimitBlock, nowWall: Long, nowElapsed: Long) {
        val until = Instant.ofEpochMilli(block.untilWall).atZone(zone).format(HOUR_FORMAT)
        val content = when (block.kind) {
            LimitBlock.Kind.BEDTIME -> {
                val allowance = bedtimeProvider().nightAllowanceMinutes
                OverlayContent(
                    title = context.getString(R.string.overlay_bedtime_title),
                    message = if (allowance > 0) {
                        context.getString(R.string.overlay_bedtime_allowance_message, allowance, app.displayName, until)
                    } else {
                        context.getString(R.string.overlay_bedtime_message, app.displayName, until)
                    },
                    actionLabel = context.getString(R.string.overlay_close_app),
                    style = OverlayStyle.NIGHT,
                )
            }
            LimitBlock.Kind.DAILY -> {
                val nextMidnight = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate().plusDays(1)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                OverlayContent(
                    title = context.getString(R.string.overlay_limit_title, app.displayName, app.limitLabel),
                    message = if (block.untilWall == nextMidnight) {
                        context.getString(R.string.overlay_limit_message)
                    } else {
                        context.getString(R.string.overlay_limit_until_message, until)
                    },
                    actionLabel = context.getString(R.string.overlay_go_home),
                )
            }
            LimitBlock.Kind.HOURLY -> OverlayContent(
                title = context.getString(
                    R.string.overlay_hourly_title,
                    app.displayName,
                    app.hourlyLimitLabel.orEmpty(),
                ),
                message = context.getString(R.string.overlay_hourly_message, until),
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
                bedtime = bedtimeProvider(),
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
