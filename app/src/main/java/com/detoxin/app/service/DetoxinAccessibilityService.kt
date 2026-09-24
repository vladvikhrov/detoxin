package com.detoxin.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
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
 */
class DetoxinAccessibilityService : AccessibilityService() {

    private lateinit var serviceScope: CoroutineScope
    private lateinit var logEventEngine: LogEventEngine
    private lateinit var overlayManager: OverlayManager
    private lateinit var usageTracker: AppUsageTracker
    private lateinit var powerManager: PowerManager

    private val scanRequests = Channel<String>(Channel.CONFLATED)
    private val suppressedUntil = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        powerManager = getSystemService(PowerManager::class.java)

        val database = DatabaseProvider.getDatabase(this)
        logEventEngine = LogEventEngine(database.blockedAttemptDao())
        overlayManager = OverlayManager(this)
        usageTracker = AppUsageTracker(
            context = this,
            overlayManager = overlayManager,
            tickerScope = serviceScope,
            foregroundProbe = ::foregroundPackage,
            onGoHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
        )

        serviceScope.launch { scanLoop() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        val packageName = event.packageName?.toString() ?: return

        if (TrackedApp.fromPackage(packageName) != null) {
            usageTracker.onTrackedAppEvent(packageName)
        }
        if (packageName == YouTubeShortsDetector.PACKAGE_NAME || packageName == VkFeedDetector.PACKAGE_NAME) {
            if (!isSuppressed(packageName)) scanRequests.trySend(packageName)
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility feedback interrupted by the system")
    }

    override fun onDestroy() {
        usageTracker.stop()
        overlayManager.release()
        scanRequests.close()
        logEventEngine.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun scanLoop() {
        for (packageName in scanRequests) {
            if (isSuppressed(packageName)) continue
            val blockType = detectBlockedScreen(packageName)
            if (blockType != null) block(packageName, blockType)
            // Пауза объединяет серию событий перерисовки в один проход по дереву.
            delay(SCAN_THROTTLE_MS)
        }
    }

    /** Выполняется на Dispatchers.Default. Корень окна утилизируется в любом случае. */
    private fun detectBlockedScreen(packageName: String): BlockType? {
        val root = rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != packageName) return null
            when (packageName) {
                YouTubeShortsDetector.PACKAGE_NAME -> {
                    val nodes = NodeTreeScanner.capture(root, YouTubeShortsDetector.anchorQueries)
                    if (YouTubeShortsDetector.isShortsOpen(nodes)) BlockType.SHORTS else null
                }
                VkFeedDetector.PACKAGE_NAME -> {
                    val nodes = NodeTreeScanner.capture(root, VkFeedDetector.anchorQueries)
                    VkFeedDetector.detect(nodes)
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

    private fun block(packageName: String, blockType: BlockType) {
        suppressedUntil[packageName] = SystemClock.elapsedRealtime() + SUPPRESSION_WINDOW_MS
        if (packageName == VkFeedDetector.PACKAGE_NAME) {
            // ВК открывается на «Главной»: «Назад» с неё закрывает приложение целиком.
            // Вместо этого переводим пользователя в сообщения.
            if (!clickVkMessengerTab()) openVkMessenger()
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        logEventEngine.submit(
            BlockedAttempt(
                blockType = blockType,
                timestamp = System.currentTimeMillis(),
                dateEpochDay = LocalDate.now().toEpochDay(),
            )
        )
    }

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
    }
}
