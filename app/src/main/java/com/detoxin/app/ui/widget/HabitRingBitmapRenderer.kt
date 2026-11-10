package com.detoxin.app.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

/**
 * Процедурный циферблат цикла привычки для Glance-виджета (Glance не поддерживает
 * Compose Canvas, поэтому рисование идёт через `android.graphics.Canvas` в [Bitmap]).
 *
 * Геометрия (при размере [SIZE_PX] = 240 px, ARGB_8888 ≈ 225 КБ):
 *  - кольцо — сплошная полоса: приглушённый [TRACK_COLOR] по всему кругу и дуга прогресса
 *    цветом привычки со свечением (`setShadowLayer`), от «12 часов» по часовой стрелке;
 *  - центр: залитый диск с галочкой, если сегодня выполнено, иначе контурная окружность —
 *    приглашение к касанию. Число дня цикла — крупно в центре, «/30» под ним.
 *
 * Объект не хранит состояния; все Paint создаются на вызов, поэтому рендер потокобезопасен
 * и может выполняться в фоновой композиции Glance.
 */
internal object HabitRingBitmapRenderer {

    const val SIZE_PX = 240
    private const val STROKE_PX = 12f
    private const val GLOW_RADIUS_PX = 6f
    private const val EDGE_PADDING_PX = GLOW_RADIUS_PX + 2f
    private const val CENTER_RADIUS_PX = 62f

    private val TRACK_COLOR = Color.parseColor("#2A313A")
    private const val MUTED_TEXT_COLOR = 0xFF9AA4B0.toInt()

    /**
     * @param progress выполненные дни цикла (ограничиваются [cycleLength]).
     * @param cycleLength длина цикла; доля заполнения кольца — `progress / cycleLength`.
     */
    fun render(progress: Int, cycleLength: Int, completedToday: Boolean, colorArgb: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = SIZE_PX / 2f
        val length = max(cycleLength, 1)
        val clamped = progress.coerceIn(0, length)
        drawSegments(canvas, center, clamped, length, colorArgb)
        drawCenter(canvas, center, clamped, length, completedToday, colorArgb)
        return bitmap
    }

    /** Сплошная полоса заполнения: серый трек по всему кругу и дуга прогресса сверху по часовой стрелке. */
    private fun drawSegments(canvas: Canvas, center: Float, progress: Int, length: Int, color: Int) {
        val radius = center - EDGE_PADDING_PX - STROKE_PX / 2f
        val oval = RectF(center - radius, center - radius, center + radius, center + radius)
        canvas.drawCircle(center, center, radius, strokePaint(TRACK_COLOR))
        if (progress <= 0) return
        val active = strokePaint(color).apply { setShadowLayer(GLOW_RADIUS_PX, 0f, 0f, withAlpha(color, 0x99)) }
        if (progress >= length) {
            canvas.drawCircle(center, center, radius, active.apply { strokeCap = Paint.Cap.BUTT })
        } else {
            canvas.drawArc(oval, -90f, 360f * progress / length, false, active)
        }
    }

    private fun drawCenter(
        canvas: Canvas,
        center: Float,
        progress: Int,
        length: Int,
        completedToday: Boolean,
        color: Int,
    ) {
        val onDisc = if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE
        if (completedToday) {
            canvas.drawCircle(center, center, CENTER_RADIUS_PX, fillPaint(color))
            drawCheck(canvas, center, center - 16f, onDisc)
            drawText(canvas, "$progress/$length", center, center + 34f, 26f, onDisc, bold = true)
        } else {
            val outline = strokePaint(withAlpha(color, 0xB3)).apply {
                strokeWidth = 4f
                strokeCap = Paint.Cap.BUTT
            }
            canvas.drawCircle(center, center, CENTER_RADIUS_PX, outline)
            drawText(canvas, progress.toString(), center, center + 8f, 52f, Color.WHITE, bold = true)
            drawText(canvas, "/$length", center, center + 38f, 20f, MUTED_TEXT_COLOR, bold = false)
        }
    }

    /** Галочка: ломаная из двух отрезков с круглыми концами. */
    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx - 20f, cy)
            lineTo(cx - 6f, cy + 14f)
            lineTo(cx + 22f, cy - 14f)
        }
        val paint = strokePaint(color).apply {
            strokeWidth = 8f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, paint)
    }

    /** Текст, центрированный по X; [baselineY] — базовая линия. */
    private fun drawText(canvas: Canvas, text: String, cx: Float, baselineY: Float, sizePx: Float, color: Int, bold: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            textAlign = Paint.Align.CENTER
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        canvas.drawText(text, cx, baselineY, paint)
    }

    private fun strokePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = STROKE_PX
        this.color = color
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
