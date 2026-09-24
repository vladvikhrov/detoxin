package com.detoxin.app.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Процедурный циферблат цикла привычки для Glance-виджета (Glance не поддерживает
 * Compose Canvas, поэтому рисование идёт через `android.graphics.Canvas` в [Bitmap]).
 *
 * Геометрия (при размере [SIZE_PX] = 240 px, ARGB_8888 ≈ 225 КБ):
 *  - окружность делится на [SEGMENTS] секторов по 12°; видимая дуга каждого — [VISIBLE_SWEEP_DEG],
 *    остальное — зазор. Круглые концы (`Cap.ROUND`) удлиняют дугу на `strokeWidth / 2` с каждой
 *    стороны, поэтому их угловой размер вычитается из sweep — иначе зазоры бы «слиплись»;
 *  - первые `K` сегментов — цвет привычки со свечением (`setShadowLayer`), текущий сегмент ярче;
 *    остальные — приглушённый [TRACK_COLOR];
 *  - центр: залитый диск с галочкой, если сегодня выполнено, иначе контурная окружность —
 *    приглашение к касанию. Число дня цикла — крупно в центре, «/30» под ним.
 *
 * Объект не хранит состояния; все Paint создаются на вызов, поэтому рендер потокобезопасен
 * и может выполняться в фоновой композиции Glance.
 */
internal object HabitRingBitmapRenderer {

    const val SIZE_PX = 240
    const val SEGMENTS = 30

    private const val SEGMENT_DEG = 360f / SEGMENTS
    private const val VISIBLE_SWEEP_DEG = 8.5f
    private const val STROKE_PX = 12f
    private const val GLOW_RADIUS_PX = 6f
    private const val EDGE_PADDING_PX = GLOW_RADIUS_PX + 2f
    private const val CENTER_RADIUS_PX = 62f

    private val TRACK_COLOR = Color.parseColor("#2A313A")
    private const val MUTED_TEXT_COLOR = 0xFF9AA4B0.toInt()

    /**
     * @param progress выполненные дни цикла (ограничиваются [cycleLength]).
     * @param cycleLength длина цикла; кольцо всегда из 30 сегментов, прогресс масштабируется.
     */
    fun render(progress: Int, cycleLength: Int, completedToday: Boolean, colorArgb: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = SIZE_PX / 2f
        val length = max(cycleLength, 1)
        val clamped = progress.coerceIn(0, length)
        val filledSegments = if (clamped == 0) 0 else max(1, (clamped * SEGMENTS + length - 1) / length)

        drawSegments(canvas, center, filledSegments, colorArgb)
        drawCenter(canvas, center, clamped, length, completedToday, colorArgb)
        return bitmap
    }

    private fun drawSegments(canvas: Canvas, center: Float, filled: Int, color: Int) {
        val radius = center - EDGE_PADDING_PX - STROKE_PX / 2f
        val oval = RectF(center - radius, center - radius, center + radius, center + radius)
        val capDeg = Math.toDegrees((STROKE_PX / 2f / radius).toDouble()).toFloat()
        val drawnSweep = max(VISIBLE_SWEEP_DEG - 2f * capDeg, 0.5f)
        val gapHalf = (SEGMENT_DEG - drawnSweep) / 2f

        val track = strokePaint(TRACK_COLOR)
        val active = strokePaint(color).apply { setShadowLayer(GLOW_RADIUS_PX, 0f, 0f, withAlpha(color, 0x99)) }
        val current = strokePaint(lighten(color, 0.35f)).apply {
            setShadowLayer(GLOW_RADIUS_PX * 1.4f, 0f, 0f, withAlpha(color, 0xCC))
        }

        for (index in 0 until SEGMENTS) {
            // Первый сегмент начинается сверху (-90°) и идёт по часовой стрелке.
            val start = -90f + index * SEGMENT_DEG + gapHalf
            val paint = when {
                index >= filled -> track
                index == filled - 1 -> current
                else -> active
            }
            canvas.drawArc(oval, start, drawnSweep, false, paint)
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

    /** Смешивание с белым: яркий оттенок для текущего сегмента. */
    private fun lighten(color: Int, amount: Float): Int {
        fun channel(value: Int) = min(255, (value + (255 - value) * amount).toInt())
        return Color.argb(Color.alpha(color), channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
    }
}
