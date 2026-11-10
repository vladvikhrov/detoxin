package com.detoxin.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.detoxin.app.core.HardBlockedApp
import com.detoxin.app.guard.BootClock
import com.detoxin.app.guard.GuardRepository
import com.detoxin.app.guard.GuardState
import com.detoxin.app.service.detector.BrowserUrlPolicy
import com.detoxin.app.service.detector.InstallScreenDetector
import com.detoxin.app.util.DayRollover
import kotlinx.coroutines.flow.StateFlow
import com.detoxin.app.bedtime.BedtimeHabitEvaluator
import com.detoxin.app.bedtime.BedtimeRepository
import com.detoxin.app.bedtime.BedtimeScheduler
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.entity.BlockType
import com.detoxin.app.data.local.entity.BlockedAttempt
import com.detoxin.app.overlay.OverlayManager
import com.detoxin.app.service.detector.NodeTreeScanner
import com.detoxin.app.service.detector.VkFeedDetector
import com.detoxin.app.service.detector.YouTubeShortsDetector
import com.detoxin.app.service.detector.matchesKeyword
import com.detoxin.app.service.detector.normalizeLabel
import com.detoxin.app.service.detector.recycleCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Ядро перехвата Detoxin.
 *
 * Потоки:
 *  - [onAccessibilityEvent] (Main): O(1)-фильтр по пакету, типу события и окну подавления.
 *    Для YouTube и TikTok событие также отмечает приложение как активное в [AppUsageTracker];
 *    для YouTube и VK имя пакета кладётся в CONFLATED-канал анализа экрана.
 *  - Сканер (Dispatchers.Default): единственный воркер снимает дерево нод
 *    ([NodeTreeScanner]), применяет эвристики и при детекте выполняет BACK.
 *  - Запись в БД (Dispatchers.IO): [LogEventEngine] (блокировки) и синхронизация системной
 *    статистики экранного времени, которую запускает [AppUsageTracker].
 *
 * YouTube учитывается одновременно двумя механизмами: Shorts закрываются через BACK,
 * а обычные видео честно накапливают экранное время до суточного лимита.
 *
 * Anti-Loop: после BACK пакет попадает в [suppressedUntil] на [SUPPRESSION_WINDOW_MS];
 * события этого пакета не анализируются, пока закрываемый экран анимируется.
 *
 * Полная блокировка ([HardBlockedApp]): запуск TikTok, YouTube и Яндекс Музыки сразу возвращает
 * на главный экран, а экран их установки в магазине или установщике пакетов закрывается.
 * Браузеры: адресная строка сверяется с [BrowserUrlPolicy] — `tiktok.com` и `youtube.com/shorts`
 * закрываются через BACK. Если BACK не помогает (страница без истории открывается снова),
 * третье срабатывание подряд уводит на главный экран.
 *
 * Экстренная разблокировка ([GuardState.emergencyActive]) ставит на паузу все перехваты и лимиты.
 *
 * Смена дня: пока экран включён, служба получает DATE_CHANGED / TIME_SET / TIMEZONE_CHANGED и
 * SCREEN_ON и через [DayRollover] переводит виджеты привычек на новый день. Полуночный
 * будильник виджета ненадёжен: он не будит устройство, а после пробуждения его успевает
 * затереть перепланирование на следующую полночь.
 *
 * «Отбой»: настройки читаются из горячего StateFlow [BedtimeRepository.state] без обращения к
 * диску. Детекторы Shorts, «Клипов» и «Ленты» работают круглосуточно, поэтому ночью они
 * действуют так же, как днём; ночной лимит добавляется к ним через [AppUsageTracker].
 * Подключение и остановка службы фиксируются [ProtectionMonitor] — так видно, если защиту
 * выключили, чтобы обойти «Отбой».
 */
class DetoxinAccessibilityService : AccessibilityService() {

    private lateinit var serviceScope: CoroutineScope
    private lateinit var logEventEngine: LogEventEngine
    private lateinit var overlayManager: OverlayManager
    private lateinit var usageTracker: AppUsageTracker
    private lateinit var powerManager: PowerManager

    private lateinit var guardState: StateFlow<GuardState>
    private var bootCount: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanRequests = Channel<String>(Channel.CONFLATED)
    private val suppressedUntil = ConcurrentHashMap<String, Long>()

    /** Серии срабатываний подряд по пакетам: `[момент последнего, длина серии]`. Только поток сканера. */
    private val repeatBlocks = HashMap<String, LongArray>()

    private var dayReceiverRegistered = false
    private val dayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            serviceScope.launch { DayRollover.ensureCurrent(applicationContext) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        powerManager = getSystemService(PowerManager::class.java)

        val database = DatabaseProvider.getDatabase(this)
        val bedtimeState = BedtimeRepository.state(this)
        guardState = GuardRepository.state(this)
        bootCount = BootClock.bootCount(this)
        logEventEngine = LogEventEngine(database.blockedAttemptDao())
        overlayManager = OverlayManager(this)
        usageTracker = AppUsageTracker(
            context = this,
            overlayManager = overlayManager,
            tickerScope = serviceScope,
            foregroundProbe = ::foregroundPackage,
            bedtimeProvider = { bedtimeState.value.effectiveAt(System.currentTimeMillis()) },
            pausedProvider = ::isEmergencyActive,
            onGoHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
        )

        serviceScope.launch { scanLoop() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Единый источник списка пакетов — код, а не XML: события остальных приложений не доставляются.
        serviceInfo = serviceInfo?.apply { packageNames = watchedPackages.toTypedArray() }
        if (!dayReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(this, dayReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            dayReceiverRegistered = true
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Сначала итог разрыва (он читает последний heartbeat), затем новые heartbeat.
                ProtectionMonitor.onProtectionStarted(this@DetoxinAccessibilityService, System.currentTimeMillis(), ZoneId.systemDefault())
                BedtimeScheduler.rescheduleFromStore(this@DetoxinAccessibilityService)
                BedtimeHabitEvaluator.evaluate(this@DetoxinAccessibilityService, System.currentTimeMillis(), ZoneId.systemDefault())
                DayRollover.ensureCurrent(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Protection start bookkeeping failed", e)
            }
            while (isActive) {
                // При выключенном экране TikTok открыть нельзя: heartbeat не нужен, диск не трогаем.
                if (powerManager.isInteractive) {
                    ProtectionMonitor.heartbeat(this@DetoxinAccessibilityService, System.currentTimeMillis())
                }
                delay(ProtectionMonitor.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /** Служба выключена в настройках: фиксируем начало разрыва защиты. */
    override fun onUnbind(intent: Intent?): Boolean {
        ProtectionMonitor.onProtectionStopping(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in watchedPackages) return

        val hardBlocked = HardBlockedApp.fromPackage(packageName)
        val strict = hardBlocked != null && guardState.value.isStrict(hardBlocked)
        // Приложение с полной блокировкой закрывается сразу, лимиты для него не считаются.
        if (!strict && TrackedApp.fromPackage(packageName) != null) {
            usageTracker.onTrackedAppEvent(packageName)
        }
        if (isEmergencyActive()) return
        if (strict || packageName in scannedPackages) {
            if (!isSuppressed(packageName)) scanRequests.trySend(packageName)
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility feedback interrupted by the system")
    }

    override fun onDestroy() {
        if (dayReceiverRegistered) {
            unregisterReceiver(dayReceiver)
            dayReceiverRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        ProtectionMonitor.onProtectionStopping(this)
        usageTracker.stop()
        overlayManager.release()
        scanRequests.close()
        logEventEngine.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun scanLoop() {
        for (packageName in scanRequests) {
            if (isSuppressed(packageName) || isEmergencyActive()) continue
            val verdict = inspect(packageName)
            if (verdict != null) enforce(packageName, verdict)
            // Пауза объединяет серию событий перерисовки в один проход по дереву.
            delay(SCAN_THROTTLE_MS)
        }
    }

    private enum class BlockAction { BACK, HOME, VK_MESSENGER }

    /** Что обнаружено на экране и как на это ответить; [notice] — короткое пояснение пользователю. */
    private class Verdict(val blockType: BlockType, val action: BlockAction, val notice: String? = null)

    /**
     * Выполняется на Dispatchers.Default. Событие могло прийти от окна, которое уже не активно
     * (картинка в картинке, фоновое окно), поэтому пакет сверяется с активным окном.
     * Корень окна утилизируется в любом случае.
     */
    private fun inspect(packageName: String): Verdict? {
        val root = rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != packageName) return null
            val guard = guardState.value
            val hardBlocked = HardBlockedApp.fromPackage(packageName)
            when {
                hardBlocked != null && guard.isStrict(hardBlocked) -> Verdict(
                    blockType = BlockType.APP_BLOCKED,
                    action = BlockAction.HOME,
                    notice = "${hardBlocked.displayName} заблокирован в Detoxin",
                )
                packageName == YouTubeShortsDetector.PACKAGE_NAME -> {
                    val nodes = NodeTreeScanner.capture(root, YouTubeShortsDetector.anchorQueries)
                    if (YouTubeShortsDetector.isShortsOpen(nodes)) Verdict(BlockType.SHORTS, BlockAction.BACK) else null
                }
                packageName == VkFeedDetector.PACKAGE_NAME -> {
                    val nodes = NodeTreeScanner.capture(root, VkFeedDetector.anchorQueries)
                    VkFeedDetector.detect(nodes)?.let { Verdict(it, BlockAction.VK_MESSENGER) }
                }
                packageName in InstallScreenDetector.packages -> {
                    val strictApps = HardBlockedApp.entries.filter(guard::isStrict)
                    if (strictApps.isEmpty()) return null
                    val nodes = NodeTreeScanner.capture(root, InstallScreenDetector.anchorQueries(strictApps))
                    InstallScreenDetector.detect(packageName, nodes, strictApps)?.let { app ->
                        Verdict(
                            blockType = BlockType.INSTALL_BLOCKED,
                            action = BlockAction.BACK,
                            notice = "Установка ${app.displayName} заблокирована в Detoxin",
                        )
                    }
                }
                packageName in BrowserUrlPolicy.packages -> {
                    readAddressBar(root, packageName).firstNotNullOfOrNull(BrowserUrlPolicy::classify)?.let { type ->
                        Verdict(type, BlockAction.BACK, notice = "Эта страница закрыта в Detoxin")
                    }
                }
                else -> null
            }
        } catch (e: IllegalStateException) {
            // Окно закрылось или нода устарела во время обхода — пропускаем этот проход.
            Log.w(TAG, "Node tree changed during scan of $packageName", e)
            null
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * Текст адресной строки браузера. Сначала точечный поиск по известным resource-id (выполняется
     * на стороне браузера, без обхода дерева страницы). Если id не найден — запасной поиск по
     * тексту адреса, но только среди нативных View с id, похожим на адресную строку: текст
     * самой веб-страницы (например, поисковая выдача со словом tiktok.com) не учитывается.
     * Строка в фокусе пропускается: пользователь ещё набирает адрес. Все ноды утилизируются.
     */
    private fun readAddressBar(root: AccessibilityNodeInfo, packageName: String): List<String> {
        val texts = ArrayList<String>(2)
        var barFound = false
        for (id in BrowserUrlPolicy.urlBarIds(packageName)) {
            val found = root.findAccessibilityNodeInfosByViewId("$packageName:id/$id") ?: continue
            try {
                for (node in found) {
                    barFound = true
                    if (!node.isFocused) node.text?.toString()?.let(texts::add)
                }
            } finally {
                found.forEach { it.recycleCompat() }
            }
        }
        if (barFound) return texts

        for (query in BrowserUrlPolicy.fallbackQueries) {
            val found = root.findAccessibilityNodeInfosByText(query) ?: continue
            try {
                for (node in found) {
                    val viewId = node.viewIdResourceName ?: continue
                    if (!viewId.startsWith("$packageName:id/")) continue
                    if (!BrowserUrlPolicy.looksLikeUrlBarId(viewId.substringAfter(":id/"))) continue
                    if (!node.isFocused) node.text?.toString()?.let(texts::add)
                }
            } finally {
                found.forEach { it.recycleCompat() }
            }
        }
        return texts
    }

    /** Выполняется на Dispatchers.Default (поток сканера). */
    private fun enforce(packageName: String, verdict: Verdict) {
        val nowElapsed = SystemClock.elapsedRealtime()
        suppressedUntil[packageName] = nowElapsed + SUPPRESSION_WINDOW_MS
        when (verdict.action) {
            BlockAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            // ВК открывается на «Главной»: «Назад» с неё закрывает приложение целиком.
            // Вместо этого переводим пользователя в сообщения.
            BlockAction.VK_MESSENGER -> if (!clickVkMessengerTab()) openVkMessenger()
            BlockAction.BACK -> if (isRepeatedBlock(packageName, nowElapsed)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        verdict.notice?.let(::showNotice)
        logEventEngine.submit(
            BlockedAttempt(
                blockType = verdict.blockType,
                timestamp = System.currentTimeMillis(),
                dateEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay(),
            )
        )
    }

    /**
     * Защита от зацикливания BACK: если тот же пакет блокируется [ESCALATION_COUNT]-й раз подряд
     * с промежутками короче [ESCALATION_WINDOW_MS], «Назад» не уводит с экрана (вкладка без
     * истории, редирект) — тогда вместо него выполняется HOME.
     */
    private fun isRepeatedBlock(packageName: String, nowElapsed: Long): Boolean {
        val series = repeatBlocks.getOrPut(packageName) { longArrayOf(0L, 0L) }
        series[1] = if (nowElapsed - series[0] <= ESCALATION_WINDOW_MS) series[1] + 1L else 1L
        series[0] = nowElapsed
        if (series[1] < ESCALATION_COUNT) return false
        series[1] = 0L
        return true
    }

    private fun showNotice(text: String) {
        mainHandler.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    private fun isEmergencyActive(): Boolean =
        guardState.value.emergencyActive(SystemClock.elapsedRealtime(), bootCount)

    /**
     * Нажимает вкладку «Мессенджер» в нижней панели ВК. Подпись обычно лежит в некликабельном
     * TextView, поэтому клик отправляется ближайшему кликабельному предку (до [MAX_CLICK_ANCESTORS]
     * уровней). Все полученные ноды утилизируются. Выполняется на Dispatchers.Default.
     */
    private fun clickVkMessengerTab(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != VkFeedDetector.PACKAGE_NAME) return false
            for (query in VkFeedDetector.messengerQueries) {
                val found = root.findAccessibilityNodeInfosByText(query) ?: continue
                try {
                    for (node in found) {
                        val label = node.text?.toString().normalizeLabel()
                            ?: node.contentDescription?.toString().normalizeLabel()
                            ?: continue
                        if (!label.matchesKeyword(VkFeedDetector.messengerKeywords)) continue
                        if (clickSelfOrAncestor(node)) return true
                    }
                } finally {
                    found.forEach { it.recycleCompat() }
                }
            }
            return false
        } finally {
            root.recycleCompat()
        }
    }

    /** Клик по ноде или ближайшему кликабельному предку; каждый полученный предок утилизируется. */
    private fun clickSelfOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var current = node.parent ?: return false
        try {
            var depth = 1
            while (true) {
                if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (depth++ >= MAX_CLICK_ANCESTORS) return false
                val next = current.parent ?: return false
                current.recycleCompat()
                current = next
            }
        } finally {
            current.recycleCompat()
        }
    }

    /** Запасной путь, если вкладку не нашли: ссылка на сообщения, открываемая самим приложением ВК. */
    private fun openVkMessenger() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(VK_MESSENGER_URL))
            .setPackage(VkFeedDetector.PACKAGE_NAME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "VK cannot open messenger link, falling back to BACK", e)
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun isSuppressed(packageName: String): Boolean {
        val until = suppressedUntil[packageName] ?: return false
        if (SystemClock.elapsedRealtime() < until) return true
        suppressedUntil.remove(packageName, until)
        return false
    }

    /**
     * Пакет активного окна или `null`, если экран выключен.
     * Binder-запрос; вызывается трекером на Dispatchers.Default.
     */
    private fun foregroundPackage(): String? {
        if (!powerManager.isInteractive) return null
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycleCompat()
        }
    }

    private companion object {
        const val TAG = "DetoxinA11yService"
        const val SUPPRESSION_WINDOW_MS = 1_200L
        const val SCAN_THROTTLE_MS = 250L
        const val MAX_CLICK_ANCESTORS = 4
        const val VK_MESSENGER_URL = "https://vk.com/im"
        const val ESCALATION_WINDOW_MS = 5_000L
        const val ESCALATION_COUNT = 3L

        /** Пакеты, экран которых анализируется сканером независимо от полной блокировки. */
        val scannedPackages: Set<String> = setOf(YouTubeShortsDetector.PACKAGE_NAME, VkFeedDetector.PACKAGE_NAME) +
            InstallScreenDetector.packages + BrowserUrlPolicy.packages

        /** Все пакеты, события которых нужны службе. */
        val watchedPackages: Set<String> = scannedPackages + TrackedApp.packageNames + HardBlockedApp.packageNames
    }
}
