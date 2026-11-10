package com.detoxin.app.ui.relapse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.detoxin.app.util.formatDurationShort

/** «Стена стыда»: когда защита была выключена и что в это время было открыто в обход. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelapseScreen(
    onBack: () -> Unit,
    viewModel: RelapseViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.syncUsage()
        onPauseOrDispose { }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Стена стыда") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { innerPadding ->
        val current = state
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SummaryCard(current)
            TimelineCard(current)
            if (current.entries.isEmpty()) {
                Text(
                    "За последние ${current.days} дней защита не выключалась. Так держать.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Срывы", style = MaterialTheme.typography.titleMedium)
                current.entries.forEach { entry -> EntryCard(entry) }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: RelapseUiState) {
    val clean = state.relapseCount == 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (clean) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (clean) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Последние ${state.days} дней", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Без защиты", formatDurationShort(state.totalUnprotectedMs), Modifier.weight(1f))
                Stat("В обход Detoxin", formatDurationShort(state.totalBypassMs), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Срывов", state.relapseCount.toString(), Modifier.weight(1f))
                Stat("Самый долгий", formatDurationShort(state.longestMs), Modifier.weight(1f))
                Stat("Дней без срывов", state.cleanDays.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TimelineCard(state: RelapseUiState) {
    val gapColor = MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
    val bypassColor = MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Шкала времени", style = MaterialTheme.typography.titleMedium)
            RelapseTimeline(
                rows = state.rows,
                gapColor = gapColor,
                bypassColor = bypassColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((state.rows.size * ROW_HEIGHT_DP + AXIS_HEIGHT_DP).dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(gapColor, "защита выключена")
                Legend(bypassColor, "открыто в обход")
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(12.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx())) }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Временная шкала на чистом Canvas: строка — день (сверху сегодня), ось X — сутки 00–24.
 * Подписи измеряются и геометрия считается один раз в `drawWithCache`; в фазе рисования только
 * примитивы.
 */
@Composable
private fun RelapseTimeline(
    rows: List<RelapseDayRow>,
    gapColor: Color,
    bypassColor: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val todayStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val gapCount = rows.sumOf { it.gaps.size }

    Canvas(
        modifier = modifier
            .semantics { contentDescription = "Шкала отключений защиты: отрезков $gapCount за ${rows.size} дней" }
            .drawWithCache {
                val rowHeight = ROW_HEIGHT_DP.dp.toPx()
                val barHeight = BAR_HEIGHT_DP.dp.toPx()
                val labels = rows.map { textMeasurer.measure(it.label, if (it.isToday) todayStyle else labelStyle) }
                val labelWidth = (labels.maxOfOrNull { it.size.width } ?: 0) + 8.dp.toPx()
                val chartLeft = labelWidth
                val chartWidth = (size.width - chartLeft).coerceAtLeast(1f)
                val hours = listOf(0, 6, 12, 18, 24)
                val hourLabels = hours.map { textMeasurer.measure("%02d".format(it), labelStyle) }
                val axisTop = rows.size * rowHeight
                val corner = CornerRadius(barHeight / 2f)
                val minWidth = 2.dp.toPx()

                onDrawBehind {
                    hours.forEachIndexed { index, hour ->
                        val x = chartLeft + chartWidth * hour / 24f
                        drawLine(gridColor, Offset(x, 0f), Offset(x, axisTop), strokeWidth = 1.dp.toPx())
                        val label = hourLabels[index]
                        val labelX = (x - label.size.width / 2f).coerceIn(chartLeft, size.width - label.size.width)
                        drawText(label, topLeft = Offset(labelX, axisTop + 4.dp.toPx()))
                    }
                    rows.forEachIndexed { index, row ->
                        val top = index * rowHeight + (rowHeight - barHeight) / 2f
                        drawText(
                            labels[index],
                            topLeft = Offset(0f, index * rowHeight + (rowHeight - labels[index].size.height) / 2f),
                        )
                        drawRoundRect(trackColor, Offset(chartLeft, top), Size(chartWidth, barHeight), corner)
                        row.gaps.forEach { span ->
                            val width = (chartWidth * (span.end - span.start)).coerceAtLeast(minWidth)
                            drawRoundRect(gapColor, Offset(chartLeft + chartWidth * span.start, top), Size(width, barHeight), corner)
                        }
                        row.bypass.forEach { span ->
                            val width = (chartWidth * (span.end - span.start)).coerceAtLeast(minWidth)
                            drawRect(bypassColor, Offset(chartLeft + chartWidth * span.start, top), Size(width, barHeight))
                        }
                    }
                }
            },
    ) {}
}

@Composable
private fun EntryCard(entry: RelapseEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    formatDurationShort(entry.durationMs),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(entry.period, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (entry.bypassByApp.isEmpty()) {
                    "Отслеживаемые приложения не открывались"
                } else {
                    "В обход: " + entry.bypassByApp.joinToString(", ") { (name, ms) -> "$name ${formatDurationShort(ms)}" }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private const val ROW_HEIGHT_DP = 22
private const val BAR_HEIGHT_DP = 10
private const val AXIS_HEIGHT_DP = 24
