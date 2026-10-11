package com.detoxin.app.ui.common.charts

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Чистая математика графиков: тренд «вторая половина периода против первой», «красивая»
 * верхняя граница оси и монотонная интерполяция для сплайна. Без Android-зависимостей.
 */
object TrendMath {

    /**
     * Изменение среднего второй половины периода относительно первой, в целых процентах.
     * Средняя точка нечётного ряда не участвует, `null`-значения пропускаются.
     *
     * @return `null`, если сравнивать не с чем: в одной из половин нет данных, либо в режиме
     * [TrendMode.RELATIVE] первая половина нулевая (рост «с нуля» в процентах не выражается).
     */
    fun percentChange(values: List<Float?>, mode: TrendMode): Int? {
        val half = values.size / 2
        if (half == 0) return null
        val first = mean(values.subList(0, half)) ?: return null
        val second = mean(values.subList(values.size - half, values.size)) ?: return null
        val change = when (mode) {
            TrendMode.ABSOLUTE -> second - first
            TrendMode.RELATIVE -> {
                if (first <= 0f) return null
                (second - first) / first
            }
        }
        return (change * 100f).roundToInt()
    }

    /**
     * Верхняя граница оси Y: ближайшее «круглое» число (1, 2, 2.5, 5 × 10ⁿ) не меньше
     * значения с запасом 15% — пик не упирается в тултип, подписи читаются без дробей.
     */
    fun niceMax(value: Float, minimum: Float): Float {
        val target = maxOf(value * HEADROOM, minimum)
        if (target <= 0f) return 1f
        var magnitude = 1f
        while (magnitude * 10f <= target) magnitude *= 10f
        while (magnitude > target) magnitude /= 10f
        for (step in NICE_STEPS) {
            val candidate = step * magnitude
            if (candidate >= target) return candidate
        }
        return 10f * magnitude
    }

    /**
     * Касательные монотонного кубического сплайна Эрмита (Фрич–Карлсон): ограничены так,
     * чтобы кривая не выходила за пределы соседних значений — не уходила ниже нуля и не
     * рисовала ложных пиков между точками.
     */
    fun monotoneTangents(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        val tangents = FloatArray(n)
        if (n < 2) return tangents
        val secants = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        tangents[0] = secants[0]
        tangents[n - 1] = secants[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = if (secants[i - 1] * secants[i] <= 0f) 0f else (secants[i - 1] + secants[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (abs(secants[i]) < EPSILON) {
                tangents[i] = 0f
                tangents[i + 1] = 0f
            } else {
                val a = tangents[i] / secants[i]
                val b = tangents[i + 1] / secants[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / kotlin.math.sqrt(s)
                    tangents[i] = tau * a * secants[i]
                    tangents[i + 1] = tau * b * secants[i]
                }
            }
        }
        return tangents
    }

    private fun mean(values: List<Float?>): Float? {
        var sum = 0f
        var count = 0
        for (value in values) {
            if (value == null) continue
            sum += value
            count++
        }
        return if (count == 0) null else sum / count
    }

    private const val HEADROOM = 1.15f
    private const val EPSILON = 1e-6f
    private val NICE_STEPS = floatArrayOf(1f, 2f, 2.5f, 5f, 10f)
}
