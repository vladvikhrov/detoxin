package com.detoxin.app.util

import android.content.Context
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import java.util.Locale

/**
 * Индикатор нагрузки процесса Detoxin (UI + AccessibilityService живут в одном процессе).
 *
 * Время CPU — точное значение `Process.getElapsedCpuTime()` с момента старта процесса.
 * Расход батареи — ОЦЕНКА: Android не даёт приложению измерить собственное потребление,
 * поэтому время CPU переводится в энергию по консервативной модели активного ядра
 * ([ASSUMED_CPU_POWER_MW]) и делится на ёмкость батареи, вычисленную из
 * `BATTERY_PROPERTY_CHARGE_COUNTER` и текущего уровня заряда.
 */
object AppHealthMonitor {

    private const val ASSUMED_CPU_POWER_MW = 250.0
    private const val NOMINAL_VOLTAGE_V = 3.85
    private const val FALLBACK_CAPACITY_MAH = 4_000.0
    private const val MIN_VISIBLE_PERCENT = 0.1

    /** Процессорное время процесса с момента его запуска, мс. */
    fun cpuTimeMs(): Long = Process.getElapsedCpuTime()

    /** Время жизни процесса (сеанса), мс. */
    fun processUptimeMs(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    /** Средняя загрузка одного ядра за сеанс, %. */
    fun averageCpuLoadPercent(): Double {
        val uptime = processUptimeMs()
        return if (uptime <= 0L) 0.0 else cpuTimeMs() * 100.0 / uptime
    }

    /** Оценка доли батареи, израсходованной процессом за сеанс, %. */
    fun estimatedBatteryDrainPercent(context: Context): Double {
        val cpuHours = cpuTimeMs() / 3_600_000.0
        val currentMa = ASSUMED_CPU_POWER_MW / NOMINAL_VOLTAGE_V
        val drainMah = cpuHours * currentMa
        return drainMah / batteryCapacityMah(context) * 100.0
    }

    /** «Время CPU: 12 сек • Потребление батареи: < 0.1%». */
    fun formatSummary(context: Context): String {
        val drain = estimatedBatteryDrainPercent(context)
        val drainText = if (drain < MIN_VISIBLE_PERCENT) {
            "< 0.1%"
        } else {
            String.format(Locale.US, "≈ %.1f%%", drain)
        }
        return "Время CPU: ${formatCpuTime(cpuTimeMs())} • Потребление батареи: $drainText"
    }

    private fun formatCpuTime(ms: Long): String {
        val totalSeconds = ms / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes == 0L) "$totalSeconds сек" else "$minutes мин $seconds сек"
    }

    /** Полная ёмкость = оставшийся заряд (мкА·ч) / доля заряда; при недоступности — типовые 4000 мА·ч. */
    private fun batteryCapacityMah(context: Context): Double {
        val batteryManager = context.getSystemService(BatteryManager::class.java) ?: return FALLBACK_CAPACITY_MAH
        val chargeMicroAh = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val levelPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (chargeMicroAh <= 0 || levelPercent <= 0) return FALLBACK_CAPACITY_MAH
        val capacity = chargeMicroAh / 1_000.0 / (levelPercent / 100.0)
        return if (capacity in 1_000.0..20_000.0) capacity else FALLBACK_CAPACITY_MAH
    }
}
