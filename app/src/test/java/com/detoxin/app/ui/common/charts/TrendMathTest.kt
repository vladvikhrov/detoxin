package com.detoxin.app.ui.common.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendMathTest {

    @Test
    fun relativeChangeComparesHalves() {
        // 1-я половина: среднее 100, 2-я: 50 → −50%. Средняя точка нечётного ряда пропускается.
        assertEquals(-50, TrendMath.percentChange(listOf(100f, 100f, 999f, 50f, 50f), TrendMode.RELATIVE))
        assertEquals(20, TrendMath.percentChange(listOf(50f, 60f), TrendMode.RELATIVE))
    }

    @Test
    fun absoluteChangeIsInPercentagePoints() {
        assertEquals(30, TrendMath.percentChange(listOf(0.4f, 0.4f, 0.7f, 0.7f), TrendMode.ABSOLUTE))
    }

    @Test
    fun nullsAreSkippedAndEmptyHalvesGiveNoTrend() {
        assertEquals(100, TrendMath.percentChange(listOf(10f, null, 20f, null), TrendMode.RELATIVE))
        assertNull(TrendMath.percentChange(listOf(null, null, 5f, 5f), TrendMode.RELATIVE))
        assertNull(TrendMath.percentChange(listOf(5f), TrendMode.RELATIVE))
    }

    @Test
    fun relativeGrowthFromZeroIsNotExpressible() {
        assertNull(TrendMath.percentChange(listOf(0f, 0f, 10f, 10f), TrendMode.RELATIVE))
        assertEquals(10, TrendMath.percentChange(listOf(0f, 0f, 0.1f, 0.1f), TrendMode.ABSOLUTE))
    }

    @Test
    fun niceMaxRoundsUpToFriendlyNumbers() {
        // Шаги 1 / 2 / 2.5 / 5 × 10ⁿ с запасом 15% над пиком.
        assertEquals(5f, TrendMath.niceMax(0f, 4f), 0f)
        assertEquals(10f, TrendMath.niceMax(7f, 1f), 0f)
        assertEquals(200f, TrendMath.niceMax(150f, 1f), 0f)
        assertEquals(250f, TrendMath.niceMax(210f, 1f), 0f)
        assertTrue(TrendMath.niceMax(1234f, 1f) >= 1234f * 1.15f)
    }

    @Test
    fun monotoneTangentsFlattenAtExtremes() {
        val xs = floatArrayOf(0f, 1f, 2f, 3f)
        val ys = floatArrayOf(0f, 10f, 10f, 0f)
        val tangents = TrendMath.monotoneTangents(xs, ys)
        // На плато и на пике касательные нулевые — кривая не выходит за пределы значений.
        assertEquals(0f, tangents[1], 0f)
        assertEquals(0f, tangents[2], 0f)
    }
}
