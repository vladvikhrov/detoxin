package com.detoxin.app.ui.score

import androidx.compose.runtime.Immutable
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.data.local.dto.HabitWithLogs
import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.data.local.entity.ProtectionGap
import com.detoxin.app.ui.relapse.RelapseCalculator
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.BedtimeSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Индекс цифровой гигиены 0–100 за последние [DetoxScoreCalculator.WINDOW_DAYS] дней.
 *
 * @property bedtime доля «чистых» ночей «Отбоя»; `null` — режим выключен или ночей ещё не было.
 * @property habits средняя доля выполнения нормы привычек; `null` — привычек нет.
 * @property limits доля дней без превышения дневных лимитов.
 * @property penalty штраф за срывы (периоды без защиты), уже вычтенный из [total].
 */
@Immutable
data class DetoxScore(
    val total: Int,
    val bedtime: Float?,
    val habits: Float?,
    val limits: Float,
    val penalty: Int,
) {
    val verdict: String
        get() = when {
            total >= 85 -> "Отличная гигиена"
            total >= 65 -> "Хороший режим"
            total >= 40 -> "Есть над чем работать"
            else -> "Режим разваливается"
        }
}

/**
 * Расчёт Detox Score — чистая функция.
 *
 * База — взвешенное среднее доступных компонент: лимиты экранного времени [WEIGHT_LIMITS],
 * «Отбой» [WEIGHT_BEDTIME], привычки [WEIGHT_HABITS]. Если компоненты нет (режим выключен, нет
 * привычек), её вес распределяется между остальными, а не обнуляет индекс.
 * Из базы вычитается штраф за срывы: [PENALTY_PER_GAP] за каждый период без защиты плюс балл за
 * каждые [BYPASS_MINUTES_PER_POINT] минут, проведённых в отслеживаемых приложениях в обход, но
 * не больше [MAX_PENALTY].
 */
internal object DetoxScoreCalculator {

    const val WINDOW_DAYS = 7

    private const val WEIGHT_LIMITS = 0.40f
    private const val WEIGHT_BEDTIME = 0.30f
    private const val WEIGHT_HABITS = 0.30f
    private const val PENALTY_PER_GAP = 5
    private const val BYPASS_MINUTES_PER_POINT = 5L
    private const val MAX_PENALTY = 30

    /** Секунды сверх лимита, пока экран блокировки ещё появляется, превышением не считаются. */
    private const val LIMIT_TOLERANCE_MS = 60_000L

    fun compute(
        today: LocalDate,
        zone: ZoneId,
        nowWall: Long,
        bedtime: BedtimeSettings,
        logs: List<AppUsageLog>,
        gaps: List<ProtectionGap>,
        habits: List<HabitWithLogs>,
    ): DetoxScore {
        val firstDay = today.minusDays(WINDOW_DAYS - 1L)
        val windowStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()

        val limits = limitsRatio(firstDay, today, logs)
        val nights = bedtimeRatio(today, zone, nowWall, bedtime, logs, gaps)
        val habitRatio = habitsRatio(firstDay, today, zone, habits)

        var weighted = WEIGHT_LIMITS * limits
        var weights = WEIGHT_LIMITS
        if (nights != null) {
            weighted += WEIGHT_BEDTIME * nights
            weights += WEIGHT_BEDTIME
        }
        if (habitRatio != null) {
            weighted += WEIGHT_HABITS * habitRatio
            weights += WEIGHT_HABITS
        }
        val base = weighted / weights * 100f

        val windowGaps = gaps.filter { gap ->
            gap.startedAtWall < nowWall && (gap.endedAtWall ?: nowWall) > windowStart
        }
        val bypassMinutes = RelapseCalculator.bypassMs(windowGaps, logs, windowStart, nowWall) / 60_000L
        val penalty = (windowGaps.size * PENALTY_PER_GAP + (bypassMinutes / BYPASS_MINUTES_PER_POINT).toInt())
            .coerceIn(0, MAX_PENALTY)

        return DetoxScore(
            total = (base - penalty).roundToInt().coerceIn(0, 100),
            bedtime = nights,
            habits = habitRatio,
            limits = limits,
            penalty = penalty,
        )
    }

    /** Доля дней окна, в которые ни одно приложение не превысило свой дневной лимит. */
    private fun limitsRatio(firstDay: LocalDate, today: LocalDate, logs: List<AppUsageLog>): Float {
        val perDayApp = HashMap<Pair<Long, String>, Long>()
        for (log in logs) {
            if (log.dateEpochDay !in firstDay.toEpochDay()..today.toEpochDay()) continue
            perDayApp.merge(log.dateEpochDay to log.packageName, log.durationMs, Long::plus)
        }
        val overDays = perDayApp.entries
            .filter { (key, used) ->
                val limit = TrackedApp.fromPackage(key.second)?.dailyLimitMs ?: return@filter false
                used > limit + LIMIT_TOLERANCE_MS
            }
            .map { it.key.first }
            .toSet()
        return (WINDOW_DAYS - overDays.size).toFloat() / WINDOW_DAYS
    }

    /** Доля завершённых ночей окна, прошедших в пределах ночного лимита и без отключения защиты. */
    private fun bedtimeRatio(
        today: LocalDate,
        zone: ZoneId,
        nowWall: Long,
        settings: BedtimeSettings,
        logs: List<AppUsageLog>,
        gaps: List<ProtectionGap>,
    ): Float? {
        if (!settings.enabled || !settings.hasWindow) return null
        val nights = (1..WINDOW_DAYS)
            .map { BedtimePolicy.windowStartingOn(today.minusDays(it.toLong()), zone, settings) }
            .filter { it.endWall <= nowWall }
        if (nights.isEmpty()) return null
        val clean = nights.count { night ->
            val interrupted = gaps.any { gap ->
                gap.startedAtWall < night.endWall && (gap.endedAtWall ?: nowWall) > night.startWall
            }
            val usedMs = logs.sumOf { night.overlapMs(it.sessionStartTime, it.sessionStartTime + it.durationMs) }
            !interrupted && usedMs <= settings.allowanceMs + BedtimePolicy.AUTO_HABIT_GRACE_MS
        }
        return clean.toFloat() / nights.size
    }

    /** Средняя по привычкам доля выполнения недельной нормы за дни, когда привычка уже существовала. */
    private fun habitsRatio(firstDay: LocalDate, today: LocalDate, zone: ZoneId, habits: List<HabitWithLogs>): Float? {
        val ratios = habits.mapNotNull { item ->
            val created = Instant.ofEpochMilli(item.habit.createdAt).atZone(zone).toLocalDate().toEpochDay()
            val from = maxOf(firstDay.toEpochDay(), created)
            val activeDays = (today.toEpochDay() - from + 1).toInt()
            if (activeDays <= 0) return@mapNotNull null
            val expected = (item.habit.targetDaysPerWeek.coerceIn(1, 7) * activeDays / WINDOW_DAYS.toFloat())
                .roundToInt().coerceIn(1, activeDays)
            val done = item.logs.count { it.isCompleted && it.dateEpochDay in from..today.toEpochDay() }
            (done.toFloat() / expected).coerceIn(0f, 1f)
        }
        return if (ratios.isEmpty()) null else ratios.average().toFloat()
    }
}
