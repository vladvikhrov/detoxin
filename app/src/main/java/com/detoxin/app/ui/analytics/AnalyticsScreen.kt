package com.detoxin.app.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.detoxin.app.ui.permissions.rememberPermissionStatus
import com.detoxin.app.util.AppHealthMonitor
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import com.detoxin.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import com.detoxin.app.ui.score.DetoxScore
import com.detoxin.app.ui.score.DetoxScoreGauge
import com.detoxin.app.ui.analytics.components.HabitHeatmap
import com.detoxin.app.ui.common.charts.LineSeries
import com.detoxin.app.ui.common.charts.ReferenceLine
import com.detoxin.app.ui.common.charts.TrendBadge
import com.detoxin.app.ui.common.charts.TrendLineChart
import com.detoxin.app.ui.common.charts.TrendMath
import com.detoxin.app.ui.common.charts.TrendMode
import com.detoxin.app.util.formatDurationShort
import kotlin.math.roundToInt

/** Точка входа экрана: подписка на состояние с учётом жизненного цикла. */
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel = viewModel(),
    onOpenPermissions: () -> Unit = {},
    onOpenBedtime: () -> Unit = {},
    onOpenProtection: () -> Unit = {},
    onOpenRelapse: () -> Unit = {},
    onOpenShare: () -> Unit = {},
) {
    val score by viewModel.scoreState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val night by viewModel.nightState.collectAsStateWithLifecycle()
    val range by viewModel.selectedRange.collectAsStateWithLifecycle()
    val permissions = rememberPermissionStatus()
    // Подтягиваем свежую системную статистику при каждом открытии экрана.
    LifecycleResumeEffect(Unit) {
        viewModel.syncUsage()
        onPauseOrDispose { }
    }
    AnalyticsContent(
        state = state,
        night = night,
        selectedRange = range,
        onRangeSelected = viewModel::selectRange,
        protectionEnabled = permissions.accessibilityEnabled,
        usageAccessGranted = permissions.usageAccessGranted,
        onOpenPermissions = onOpenPermissions,
        onOpenBedtime = onOpenBedtime,
        score = score,
        onOpenProtection = onOpenProtection,
        onOpenRelapse = onOpenRelapse,
        onOpenShare = onOpenShare,
    )
}

@Composable
fun AnalyticsContent(
    state: AnalyticsUiState,
    selectedRange: AnalyticsRange,
    onRangeSelected: (AnalyticsRange) -> Unit,
    night: NightStatsUiState? = null,
    protectionEnabled: Boolean = true,
    usageAccessGranted: Boolean = true,
    onOpenPermissions: () -> Unit = {},
    onOpenBedtime: () -> Unit = {},
    score: DetoxScore? = null,
    onOpenProtection: () -> Unit = {},
    onOpenRelapse: () -> Unit = {},
    onOpenShare: () -> Unit = {},
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Аналитика", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenBedtime) {
                    Icon(painterResource(R.drawable.ic_bedtime), contentDescription = "Режим «Отбой»")
                }
                IconButton(onClick = onOpenProtection) {
                    Icon(Icons.Filled.Lock, contentDescription = "Защита")
                }
            }
            if (score != null) ScoreCard(score, onOpenRelapse, onOpenShare)
            if (!protectionEnabled) {
                PermissionBanner(
                    title = "Защита выключена",
                    text = "Служба специальных возможностей Detoxin не включена: Shorts и клипы не блокируются, " +
                        "лимиты не срабатывают.",
                    onOpenPermissions = onOpenPermissions,
                )
            }
            if (!usageAccessGranted) {
                PermissionBanner(
                    title = "Экранное время не считается",
                    text = "Нет доступа к истории использования: без него Detoxin не видит, сколько вы провели " +
                        "в TikTok и YouTube, и не может применять лимиты.",
                    onOpenPermissions = onOpenPermissions,
                )
            }
            RangeSelector(selectedRange, onRangeSelected)
            when (state) {
                AnalyticsUiState.Loading -> LoadingState()
                is AnalyticsUiState.Empty -> EmptyState(state.range)
                is AnalyticsUiState.Success -> SuccessContent(state)
            }
            if (night != null) NightCard(night)
            HealthFooter()
        }
    }
}

@Composable
private fun ScoreCard(score: DetoxScore, onOpenRelapse: () -> Unit, onOpenShare: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Detox Score · 7 дней", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            DetoxScoreGauge(score = score.total, modifier = Modifier.fillMaxWidth(0.7f))
            Text(score.verdict, style = MaterialTheme.typography.titleMedium)
            // Составляющие — отдельными колонками: одной строкой через «·» они переносились.
            Row(modifier = Modifier.fillMaxWidth()) {
                listOfNotNull(
                    "${(score.limits * 100).roundToInt()}%" to "лимиты",
                    score.bedtime?.let { "${(it * 100).roundToInt()}%" to "отбой" },
                    score.habits?.let { "${(it * 100).roundToInt()}%" to "привычки" },
                    ("−${score.penalty}" to "срывы").takeIf { score.penalty > 0 },
                ).forEach { (value, label) ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenRelapse) { Text("Стена стыда") }
                TextButton(onClick = onOpenShare) { Text("Карточка итогов") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(selected: AnalyticsRange, onSelected: (AnalyticsRange) -> Unit) {
    val ranges = AnalyticsRange.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ranges.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelected(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ranges.size),
            ) {
                Text(range.title)
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(range: AnalyticsRange) {
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(modifier = Modifier.size(120.dp)) {
            val stroke = 3.dp.toPx()
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - stroke,
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                ),
            )
            val barWidth = size.width * 0.12f
            val baseY = size.height * 0.68f
            val heights = floatArrayOf(0.18f, 0.32f, 0.24f)
            heights.forEachIndexed { index, fraction ->
                val height = size.height * fraction
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(size.width * 0.29f + index * barWidth * 1.5f, baseY - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 3f),
                )
            }
        }
        Text("Пока нет данных", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "За период «${range.title.lowercase()}» ещё ничего не записано. " +
                "Включите службу Detoxin в настройках специальных возможностей и добавьте " +
                "первую привычку — статистика появится автоматически.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SuccessContent(state: AnalyticsUiState.Success) {
    SummaryRow(state.summary)

    val usageColors = UsageSeriesColors.current()
    val points = state.usage.points
    val totals = points.map { point -> point.totalMs?.let(::msToMinutes) }
    val limitCaption = if (state.range == AnalyticsRange.DAY) "в час" else "в день"
    val periodWord = periodWord(state.range)
    ChartCard(
        title = "Экранное время",
        subtitle = "Лимит: ${formatDurationShort(state.usage.limitMs)} $limitCaption · коснитесь графика",
        trailing = { RangeTrend(state.range, totals, risingIsGood = false) },
    ) {
        TrendLineChart(
            series = listOf(
                LineSeries("Всего", usageColors.total, totals),
                LineSeries("TikTok", usageColors.tikTok, points.map { point -> point.tikTokMs?.let(::msToMinutes) }),
                LineSeries("YouTube", usageColors.youTube, points.map { point -> point.youTubeMs?.let(::msToMinutes) }),
            ),
            xLabels = points.map { it.axisLabel },
            tooltipTitles = points.map { it.tooltipTitle },
            yFormatter = ::formatMinutesValue,
            minYMax = MIN_USAGE_AXIS_MINUTES,
            referenceLine = ReferenceLine(
                value = msToMinutes(state.usage.limitMs),
                label = "лимит ${formatDurationShort(state.usage.limitMs)}",
            ),
            contentDescription = describeLine("Экранное время за $periodWord", totals, ::formatMinutesValue),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
        Spacer(Modifier.height(12.dp))
        UsageLegend(usageColors)
    }

    val blockValues = state.blocks.map { point -> point.count?.toFloat() }
    ChartCard(
        title = "Спасённые открытия",
        subtitle = "Shorts, Клипы и Лента · коснитесь или проведите по графику",
        trailing = { RangeTrend(state.range, blockValues, risingIsGood = null) },
    ) {
        TrendLineChart(
            series = listOf(LineSeries("Спасено", MaterialTheme.colorScheme.primary, blockValues)),
            xLabels = state.blocks.map { it.axisLabel },
            tooltipTitles = state.blocks.map { it.tooltipTitle },
            yFormatter = ::formatCount,
            tooltipFormatter = { value -> "${value.roundToInt()} ${attemptsWord(value.roundToInt())}" },
            minYMax = MIN_BLOCKS_AXIS,
            contentDescription = describeLine("Спасённые открытия за $periodWord", blockValues) { value ->
                "${value.roundToInt()} ${attemptsWord(value.roundToInt())}"
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
    }

    ChartCard(
        title = "Привычки",
        subtitle = "Доля выполненных привычек по дням",
    ) {
        HabitHeatmap(
            data = state.heatmap,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
    }
}

/** Тренд «вторая половина периода против первой»; для «Дня» (по часам) не показывается. */
@Composable
private fun RangeTrend(range: AnalyticsRange, values: List<Float?>, risingIsGood: Boolean?) {
    if (range == AnalyticsRange.DAY) return
    val percent = TrendMath.percentChange(values, TrendMode.RELATIVE) ?: return
    TrendBadge(percent = percent, risingIsGood = risingIsGood)
}

@Composable
private fun NightCard(night: NightStatsUiState) {
    val usageColors = UsageSeriesColors.current()
    val tikTok = night.points.map { msToMinutes(it.tikTokMs) }
    val youTube = night.points.map { msToMinutes(it.youTubeMs) }
    val totals = night.points.map { msToMinutes(it.tikTokMs + it.youTubeMs) }
    ChartCard(
        title = "Ночное время",
        subtitle = "TikTok и YouTube в окно «Отбоя» ${night.windowLabel} · ${night.points.size} ночей",
        trailing = {
            TrendMath.percentChange(totals, TrendMode.RELATIVE)?.let { percent ->
                TrendBadge(percent = percent, risingIsGood = false)
            }
        },
    ) {
        TrendLineChart(
            series = listOf(
                LineSeries("TikTok", usageColors.tikTok, tikTok),
                LineSeries("YouTube", usageColors.youTube, youTube),
            ),
            xLabels = night.points.map { it.axisLabel },
            tooltipTitles = night.points.map { it.tooltipTitle },
            yFormatter = ::formatMinutesValue,
            minYMax = MIN_NIGHT_AXIS_MINUTES,
            referenceLine = night.allowanceMinutes.takeIf { it > 0 }?.let {
                ReferenceLine(value = it.toFloat(), label = "ночной лимит $it мин")
            },
            contentDescription = describeLine("Ночное время за ${night.points.size} ночей", totals, ::formatMinutesValue),
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile(
                label = "Отбой фактически",
                value = night.averageBedtime ?: "—",
                caption = night.previousAverageBedtime?.let { "${night.previousPeriodLabel} $it" } ?: "в среднем",
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                label = "Чистые ночи",
                value = "${night.cleanNights}/${night.completedNights}",
                caption = "без TikTok сверх лимита",
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                label = "Защита выкл.",
                value = "${night.nightsWithGaps}",
                caption = nightsWord(night.nightsWithGaps),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryRow(summary: AnalyticsSummary) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryTile(
            label = "Время",
            value = formatDuration(summary.totalScreenTimeMs),
            modifier = Modifier.weight(1f),
        )
        SummaryTile(
            label = "Спасено",
            value = "${summary.totalBlocked}",
            caption = attemptsWord(summary.totalBlocked),
            modifier = Modifier.weight(1f),
        )
        SummaryTile(
            label = "Привычки",
            value = summary.averageHabitCompletion?.let { "${(it * 100f).roundToInt()}%" } ?: "—",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(value, style = MaterialTheme.typography.titleLarge)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(PaddingValues(16.dp))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing()
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** Цвета линий экранного времени: «Всего» — цвет темы, приложения — узнаваемые фирменные оттенки. */
private data class UsageSeriesColors(val total: Color, val tikTok: Color, val youTube: Color) {
    companion object {
        @Composable
        fun current() = UsageSeriesColors(
            total = MaterialTheme.colorScheme.primary,
            tikTok = TIKTOK_COLOR,
            youTube = YOUTUBE_COLOR,
        )
    }
}

@Composable
private fun UsageLegend(colors: UsageSeriesColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(color = colors.total, label = "Всего")
        LegendItem(color = colors.tikTok, label = "TikTok")
        LegendItem(color = colors.youTube, label = "YouTube")
    }
}

private fun periodWord(range: AnalyticsRange): String = when (range) {
    AnalyticsRange.DAY -> "сегодня по часам"
    AnalyticsRange.WEEK -> "неделю"
    AnalyticsRange.MONTH -> "месяц"
}

/** «1 ночь», «2 ночи», «5 ночей». */
private fun nightsWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "ночей"
        mod10 == 1 -> "ночь"
        mod10 in 2..4 -> "ночи"
        else -> "ночей"
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Предупреждение о невыданном разрешении с переходом на экран разрешений. */
@Composable
private fun PermissionBanner(title: String, text: String, onOpenPermissions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenPermissions) { Text("Настроить разрешения") }
        }
    }
}

/** Строка нагрузки процесса; обновляется раз в 5 секунд, пока экран на виду. */
@Composable
private fun HealthFooter() {
    val context = LocalContext.current
    val summary by produceState(initialValue = AppHealthMonitor.formatSummary(context)) {
        while (true) {
            delay(HEALTH_REFRESH_MS)
            value = AppHealthMonitor.formatSummary(context)
        }
    }
    Text(
        text = summary,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private const val HEALTH_REFRESH_MS = 5_000L
private const val MIN_USAGE_AXIS_MINUTES = 15f
private const val MIN_NIGHT_AXIS_MINUTES = 10f
private const val MIN_BLOCKS_AXIS = 4f
private val TIKTOK_COLOR = Color(0xFFD6457B)
private val YOUTUBE_COLOR = Color(0xFFE07A1F)

/** «0 мин», «42 мин», «3 ч 5 мин». */
private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours == 0L) "$minutes мин" else "$hours ч $minutes мин"
}
