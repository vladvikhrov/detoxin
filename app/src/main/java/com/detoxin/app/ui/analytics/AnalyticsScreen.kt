package com.detoxin.app.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.detoxin.app.ui.analytics.components.HabitHeatmap
import com.detoxin.app.ui.analytics.components.SmoothBezierLineChart
import com.detoxin.app.ui.analytics.components.StackedBarChart
import com.detoxin.app.ui.analytics.components.StackedBarDefaults
import com.detoxin.app.ui.analytics.components.attemptsWord
import com.detoxin.app.ui.analytics.components.formatMinutesShort
import kotlin.math.roundToInt

/** Точка входа экрана: подписка на состояние с учётом жизненного цикла. */
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel = viewModel(),
    onOpenPermissions: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val range by viewModel.selectedRange.collectAsStateWithLifecycle()
    val permissions = rememberPermissionStatus()
    // Подтягиваем свежую системную статистику при каждом открытии экрана.
    LifecycleResumeEffect(Unit) {
        viewModel.syncUsage()
        onPauseOrDispose { }
    }
    AnalyticsContent(
        state = state,
        selectedRange = range,
        onRangeSelected = viewModel::selectRange,
        protectionEnabled = permissions.accessibilityEnabled,
        usageAccessGranted = permissions.usageAccessGranted,
        onOpenPermissions = onOpenPermissions,
    )
}

@Composable
fun AnalyticsContent(
    state: AnalyticsUiState,
    selectedRange: AnalyticsRange,
    onRangeSelected: (AnalyticsRange) -> Unit,
    protectionEnabled: Boolean = true,
    usageAccessGranted: Boolean = true,
    onOpenPermissions: () -> Unit = {},
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
            Text("Аналитика", style = MaterialTheme.typography.headlineMedium)
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
            HealthFooter()
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

    val limitCaption = if (state.range == AnalyticsRange.DAY) "в час" else "в день"
    ChartCard(
        title = "Экранное время",
        subtitle = "Лимит: ${formatMinutesShort(state.usage.limitMs)} $limitCaption",
    ) {
        StackedBarChart(
            data = state.usage,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
        Spacer(Modifier.height(12.dp))
        UsageLegend()
    }

    ChartCard(
        title = "Спасённые открытия",
        subtitle = "Shorts, Клипы и Лента · коснитесь или проведите по графику",
    ) {
        SmoothBezierLineChart(
            points = state.blocks,
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

@Composable
private fun SummaryRow(summary: AnalyticsSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryTile(
            label = "Экранное время",
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
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(PaddingValues(16.dp))) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun UsageLegend() {
    val labels = listOf("TikTok", "YouTube", "Другие")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            LegendItem(color = StackedBarDefaults.CalmPalette[index], label = label)
        }
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

/** «0 мин», «42 мин», «3 ч 5 мин». */
private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours == 0L) "$minutes мин" else "$hours ч $minutes мин"
}
