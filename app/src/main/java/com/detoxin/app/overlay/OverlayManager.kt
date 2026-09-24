package com.detoxin.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import com.detoxin.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Текст экрана блокировки; [actionLabel] — подпись кнопки действия (например, «На главный экран»). */
data class OverlayContent(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
)

/**
 * Полноэкранный экран блокировки поверх других приложений с обратным отсчётом.
 *
 * - Окно `TYPE_APPLICATION_OVERLAY` (при выданном SYSTEM_ALERT_WINDOW). Если разрешение
 *   не выдано, используется `TYPE_ACCESSIBILITY_OVERLAY`: он доступен контексту
 *   AccessibilityService без отдельного разрешения, поэтому блокировка всё равно работает.
 * - `FLAG_NOT_FOCUSABLE`: оверлей не забирает фокус окна, активным остаётся окно
 *   заблокированного приложения, и трекер не принимает показ оверлея за уход из него.
 *   Касания при этом перехватываются (нет `FLAG_NOT_TOUCHABLE`).
 * - View строится кодом, отсчёт идёт от `SystemClock.elapsedRealtime()`, поэтому смена
 *   системного времени не сокращает блокировку.
 *
 * Все методы вызываются только из главного потока.
 */
class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var overlayView: View? = null
    private var titleText: TextView? = null
    private var messageText: TextView? = null
    private var timerText: TextView? = null
    private var actionButton: Button? = null
    private var onAction: (() -> Unit)? = null
    private var countdownJob: Job? = null
    private var endElapsed: Long = 0L
    private var onFinished: (() -> Unit)? = null

    /** Текущий текст показанного оверлея или `null`, если оверлей скрыт. */
    var currentContent: OverlayContent? = null
        @MainThread get
        private set

    val isShowing: Boolean
        @MainThread get() = overlayView != null

    /**
     * Показывает оверлей (или обновляет текст и срок, если он уже показан) до [endElapsedRealtime].
     * @return `false`, если система отказала в добавлении окна.
     */
    @MainThread
    fun show(
        content: OverlayContent,
        endElapsedRealtime: Long,
        onAction: (() -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
    ): Boolean {
        endElapsed = endElapsedRealtime
        this.onFinished = onFinished
        this.onAction = onAction
        if (overlayView == null) {
            val view = buildView()
            try {
                windowManager.addView(view, buildLayoutParams())
            } catch (e: RuntimeException) {
                // BadTokenException / SecurityException: нет прав на окно или сервис отключён.
                Log.e(TAG, "Unable to add blocking overlay", e)
                clearViewReferences()
                return false
            }
            overlayView = view
        }
        titleText?.text = content.title
        messageText?.text = content.message
        actionButton?.apply {
            text = content.actionLabel.orEmpty()
            visibility = if (content.actionLabel != null && onAction != null) View.VISIBLE else View.GONE
        }
        currentContent = content
        startCountdown()
        return true
    }

    /** Убирает оверлей, не вызывая колбэк завершения (например, пользователь ушёл из приложения). */
    @MainThread
    fun hide() {
        countdownJob?.cancel()
        countdownJob = null
        currentContent = null
        val view = overlayView ?: return
        overlayView = null
        clearViewReferences()
        // Сразу после addView isAttachedToWindow ещё false, но parent (ViewRootImpl) уже назначен.
        if (view.isAttachedToWindow || view.parent != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Overlay was already detached from WindowManager", e)
            }
        }
    }

    /** Окончательная очистка из `onDestroy` сервиса. */
    @MainThread
    fun release() {
        onFinished = null
        onAction = null
        hide()
        mainScope.cancel()
    }

    private fun clearViewReferences() {
        actionButton = null
        titleText = null
        messageText = null
        timerText = null
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = mainScope.launch {
            while (isActive) {
                val remaining = endElapsed - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    val callback = onFinished
                    onFinished = null
                    hide()
                    callback?.invoke()
                    return@launch
                }
                renderRemaining(remaining)
                // Пробуждение ровно на границе следующей секунды.
                delay(remaining % 1_000L + 1L)
            }
        }
    }

    private fun renderRemaining(remainingMs: Long) {
        val totalSeconds = (remainingMs + 999L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        timerText?.text = if (hours > 0L) {
            context.getString(R.string.overlay_timer_hours, hours, minutes, seconds)
        } else {
            context.getString(R.string.overlay_timer_minutes, minutes, seconds)
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Settings.canDrawOverlays(context)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun buildView(): View {
        val padding = dp(32f)
        val root = TouchBlockingLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BACKGROUND_COLOR)
            setPadding(padding, padding, padding, padding)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val message = TextView(context).apply {
            setTextColor(SECONDARY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(24f))
        }
        val timer = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val action = Button(context).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            visibility = View.GONE
            setOnClickListener { onAction?.invoke() }
        }
        root.addView(title, wrapContent())
        root.addView(message, wrapContent())
        root.addView(timer, wrapContent())
        root.addView(action, wrapContent().apply { topMargin = dp(32f) })
        actionButton = action
        titleText = title
        messageText = message
        timerText = timer
        return root
    }

    private fun wrapContent() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).toInt()

    /**
     * Контейнер, поглощающий все касания мимо кнопки: ни одно событие не проходит к окну под
     * оверлеем. Касания не перехватываются у дочерних View, поэтому кнопка действия работает.
     */
    private class TouchBlockingLayout(context: Context) : LinearLayout(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    private companion object {
        const val TAG = "OverlayManager"
        const val BACKGROUND_COLOR = 0xF2101418.toInt()
        const val SECONDARY_TEXT_COLOR = 0xFFB8C0CC.toInt()
    }
}
