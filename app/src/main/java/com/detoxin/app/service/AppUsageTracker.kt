package com.detoxin.app.service

import android.content.Context
import android.os.SystemClock
import androidx.annotation.MainThread
import com.detoxin.app.R
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.dao.UsageDao
import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.overlay.OverlayContent
import com.detoxin.app.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

/**
 * Универсальный учёт экранного времени [TrackedApp] (TikTok, YouTube) с суточными лимитами.
 *
 * Модель потоков: всё состояние меняется только в главном потоке (туда же приходят события
 * Accessibility). Ежесекундный таймер работает на Main, Binder-запрос активного окна
 * ([foregroundProbe]) выполняется на [Dispatchers.Default], запись в БД — [UsageWriteQueue] на IO.
 *
 * Надёжность учёта:
 *  - открытый отрезок сбрасывается в [AppUsageLog] каждые [FLUSH_INTERVAL_MS]: при
 *    убийстве процесса теряется не более 30 секунд;
 *  - отрезок, пересекающий полночь, делится: часть до 00:00 пишется во вчерашний
 *    epochDay, остаток — в сегодняшний;
 *  - длительность считается по `elapsedRealtime` (монотонные часы), а привязка к дате —
 *    по настенному времени начала отрезка.
 *
 * Лимит: сумма за сегодня из БД (кэш, загружаемый через упорядоченную очередь) + открытый
 * отрезок. При исчерпании отрезок закрывается, поверх приложения показывается оверлей с
 * отсчётом до полуночи; он скрывается при уходе из приложения и снова появляется при возврате.
 */
class AppUsageTracker(
    private val context: Context,
    usageDao: UsageDao,
    private val overlayManager: OverlayManager,
    private val tickerScope: CoroutineScope,
    private val foregroundProbe: () -> String?,
) {

    private val writeQueue = UsageWriteQueue(usageDao)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // Открытый отрезок использования.
    private var activeApp: TrackedApp? = null
    private var segmentStartElapsed: Long = 0L
    private var segmentStartWallMs: Long = 0L

    // Кэш суточных сумм из БД для [cacheDay].
    private var cacheDay: Long = Long.MIN_VALUE
    private var cacheReady: Boolean = false
    private val persistedToday = HashMap<TrackedApp, Long>()
    private val writtenWhileLoading = HashMap<TrackedApp, Long>()
    private var loadToken: Int = 0

    /** Последнее приложение на экране: таймер работает, пока там отслеживаемое приложение. */
    private var lastForeground: TrackedApp? = null
    private var tickerJob: Job? = null

    /** Событие окна отслеживаемого приложения: оно сейчас на экране. */
    @MainThread
    fun onTrackedAppEvent(packageName: String) {
        val app = TrackedApp.fromPackage(packageName) ?: return
        evaluate(app)
        ensureTicker()
    }

    /** Закрывает открытый отрезок и дописывает очередь при остановке сервиса. */
    @MainThread
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        closeSegment(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        writeQueue.close()
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = tickerScope.launch(Dispatchers.Main.immediate) {
            while (isActive && (lastForeground != null || activeApp != null || overlayManager.isShowing)) {
                delay(TICK_MS)
                val foregroundPackage = withContext(Dispatchers.Default) { foregroundProbe() }
                evaluate(TrackedApp.fromPackage(foregroundPackage))
            }
        }
    }

    /** Центральный автомат состояний, вызывается по событию и раз в секунду. */
    private fun evaluate(foreground: TrackedApp?) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val today = epochDayOf(nowWall)
        lastForeground = foreground

        if (today != cacheDay) {
            // Сначала закрываем отрезок (он разделится по полуночи), затем грузим суммы нового дня.
            if (activeApp != null) flushSegment(nowElapsed, nowWall)
            reloadCache(today)
        }

        if (activeApp != null && activeApp != foreground) closeSegment(nowElapsed, nowWall)

        if (foreground == null) {
            overlayManager.hide()
            return
        }

        if (isLimitReached(foreground, nowElapsed)) {
            if (activeApp == foreground) closeSegment(nowElapsed, nowWall)
            showLimitOverlay(foreground, nowElapsed, nowWall)
            return
        }

        if (overlayManager.isShowing) overlayManager.hide()

        if (activeApp == null) {
            activeApp = foreground
            segmentStartElapsed = nowElapsed
            segmentStartWallMs = nowWall
        } else if (nowElapsed - segmentStartElapsed >= FLUSH_INTERVAL_MS) {
            flushSegment(nowElapsed, nowWall)
        }
    }

    private fun isLimitReached(app: TrackedApp, nowElapsed: Long): Boolean {
        if (!cacheReady) return false
        val openSegment = if (activeApp == app) nowElapsed - segmentStartElapsed else 0L
        return (persistedToday[app] ?: 0L) + openSegment >= app.dailyLimitMs
    }

    private fun showLimitOverlay(app: TrackedApp, nowElapsed: Long, nowWall: Long) {
        val content = OverlayContent(
            title = context.getString(R.string.overlay_limit_title, app.displayName, app.limitLabel),
            message = context.getString(R.string.overlay_limit_message),
        )
        if (overlayManager.isShowing && overlayManager.currentContent == content) return
        val nextMidnightWall = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        overlayManager.show(content, nowElapsed + (nextMidnightWall - nowWall))
    }

    // ---------- Отрезки ----------

    /** Записывает открытый отрезок и сразу открывает следующий с текущего момента. */
    private fun flushSegment(nowElapsed: Long, nowWall: Long) {
        val app = activeApp ?: return
        persistSegment(app, nowElapsed - segmentStartElapsed, segmentStartWallMs)
        segmentStartElapsed = nowElapsed
        segmentStartWallMs = nowWall
    }

    private fun closeSegment(nowElapsed: Long, nowWall: Long) {
        flushSegment(nowElapsed, nowWall)
        activeApp = null
    }

    /** Делит отрезок по локальным полуночам и ставит части в очередь записи. */
    private fun persistSegment(app: TrackedApp, durationMs: Long, startWallMs: Long) {
        if (durationMs < MIN_SEGMENT_MS) return
        var pieceStart = startWallMs
        var remaining = durationMs
        while (remaining > 0L) {
            val date = Instant.ofEpochMilli(pieceStart).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val untilMidnight = nextMidnight - pieceStart
            val piece = if (untilMidnight > 0L) min(remaining, untilMidnight) else remaining
            recordPiece(app, pieceStart, piece, date.toEpochDay())
            remaining -= piece
            pieceStart += piece
        }
    }

    private fun recordPiece(app: TrackedApp, startWallMs: Long, durationMs: Long, dateEpochDay: Long) {
        writeQueue.write(
            AppUsageLog(
                packageName = app.packageName,
                sessionStartTime = startWallMs,
                durationMs = durationMs,
                dateEpochDay = dateEpochDay,
            )
        )
        if (dateEpochDay != cacheDay) return
        val target = if (cacheReady) persistedToday else writtenWhileLoading
        target[app] = (target[app] ?: 0L) + durationMs
    }

    // ---------- Кэш суточных сумм ----------

    /**
     * Чтение ставится в ту же очередь, что и записи: результат включает всё, что записано
     * до него, а отрезки, поставленные после, копятся в [writtenWhileLoading] и добавляются.
     */
    private fun reloadCache(day: Long) {
        cacheDay = day
        cacheReady = false
        persistedToday.clear()
        writtenWhileLoading.clear()
        val token = ++loadToken
        writeQueue.readDailyTotals(day, TrackedApp.packageNames) { totals ->
            if (token != loadToken) return@readDailyTotals
            for (app in TrackedApp.entries) {
                persistedToday[app] = (totals[app.packageName] ?: 0L) + (writtenWhileLoading[app] ?: 0L)
            }
            writtenWhileLoading.clear()
            cacheReady = true
        }
    }

    private fun epochDayOf(wallMs: Long): Long =
        Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().toEpochDay()

    private companion object {
        const val TICK_MS = 1_000L
        const val FLUSH_INTERVAL_MS = 30_000L
        const val MIN_SEGMENT_MS = 200L
    }
}
