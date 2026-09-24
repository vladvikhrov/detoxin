package com.detoxin.app.ui.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.detoxin.app.ui.habits.components.CycleSegmentsBar
import com.detoxin.app.ui.habits.components.HabitTrendBarChart
import com.detoxin.app.ui.habits.components.HabitWeekdayFrequencyChart
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Детальная статистика привычки в нижней панели. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HabitDetailSheet(
    state: HabitDetailUiState,
    selectedRange: HabitRange,
    onRangeSelected: (HabitRange) -> Unit,
    onTargetChanged: (Int) -> Unit,
    onResetCycle: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val color = habitColor(state.colorHex)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            SummaryLine(state)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val ranges = HabitRange.entries
                ranges.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = range == selectedRange,
                        onClick = { onRangeSelected(range) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ranges.size),
                    ) {
                        Text(range.title)
                    }
                }
            }

            SectionTitle(
                title = "Динамика",
                subtitle = when (state.range) {
                    HabitRange.WEEK -> "Выполнение по дням этой недели"
                    HabitRange.MONTH -> "Выполнение по дням месяца"
                    HabitRange.YEAR -> "Средний % выполнения по месяцам"
                },
            )
            HabitTrendBarChart(
                bars = state.bars,
                barColor = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )

            SectionTitle(title = "Дни недели", subtitle = "Сильные и просадочные дни за выбранный период")
            HabitWeekdayFrequencyChart(
                stats = state.weekdays,
                strongColor = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
            )

            CycleBlock(state = state, color = color, onReset = { confirmReset = true })

            TargetEditor(target = state.targetDaysPerWeek, onTargetChanged = onTargetChanged)

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.padding(start = 8.dp))
                Text("Удалить привычку")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Вы заменили линзы на новую пару?") },
            text = {
                Text(
                    "Новый цикл начнётся с сегодняшнего дня: счётчик «${state.title}» обнулится, " +
                        "а сегодняшняя отметка станет первым днём носки. История выполнений сохранится."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onResetCycle()
                    },
                ) {
                    Text("Начать новый цикл")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Отмена") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${state.title}»?") },
            text = { Text("Вся история отметок этой привычки будет удалена без возможности восстановления.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun SummaryLine(state: HabitDetailUiState) {
    val rate = if (state.possibleInRange == 0) {
        "—"
    } else {
        "${(state.completedInRange * 100f / state.possibleInRange).roundToInt()}%"
    }
    val lastDone = state.lastCompletedDay?.let {
        LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
    } ?: "ещё не было"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "За период: ${state.completedInRange} из ${state.possibleInRange} ${daysWord(state.possibleInRange)} ($rate)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Стрик: ${state.currentStreak} ${daysWord(state.currentStreak)} • всего выполнений: " +
                "${state.totalCompletions} • последнее: $lastDone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CycleBlock(state: HabitDetailUiState, color: Color, onReset: () -> Unit) {
    val isComplete = state.cycleProgress >= state.cycleLength
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Цикл использования (линзы / ${state.cycleLength} дней)",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "День ${state.cycleProgress} из ${state.cycleLength}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (isComplete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val since = state.cycleStartDay?.let {
                    "с " + LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
                } ?: "с создания"
                Text(since, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CycleSegmentsBar(
                progress = state.cycleProgress,
                cycleLength = state.cycleLength,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
            if (isComplete) {
                Text(
                    "Цикл завершён — пора заменить линзы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Начать новый цикл (сбросить на 0)")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TargetEditor(target: Int, onTargetChanged: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Норма: $target ${daysWord(target)} в неделю",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = { onTargetChanged(target - 1) }, enabled = target > 1) { Text("−") }
        FilledTonalButton(onClick = { onTargetChanged(target + 1) }, enabled = target < 7) { Text("+") }
    }
}
