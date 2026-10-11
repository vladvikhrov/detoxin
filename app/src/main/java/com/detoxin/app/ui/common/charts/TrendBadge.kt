package com.detoxin.app.ui.common.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs

/**
 * Стрелка тренда для заголовка графика: «▲ +12%», «▼ −8%», «→ 0%».
 *
 * @param risingIsGood `true` — рост окрашивается в primary, падение в error (привычки);
 * `false` — наоборот (экранное время); `null` — нейтрально.
 */
@Composable
fun TrendBadge(
    percent: Int,
    risingIsGood: Boolean?,
    modifier: Modifier = Modifier,
) {
    val good = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, color, spoken) = when {
        percent > 0 -> Triple("▲ +$percent%", if (risingIsGood == null) neutral else if (risingIsGood) good else bad, "рост на $percent%")
        percent < 0 -> Triple("▼ −${abs(percent)}%", if (risingIsGood == null) neutral else if (risingIsGood) bad else good, "снижение на ${abs(percent)}%")
        else -> Triple("→ 0%", neutral, "без изменений")
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.semantics { contentDescription = "Тренд: $spoken ко второй половине периода" },
    )
}
