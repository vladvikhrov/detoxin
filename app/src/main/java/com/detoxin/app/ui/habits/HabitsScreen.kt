package com.detoxin.app.ui.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.detoxin.app.ui.habits.components.WeekProgressRing
import kotlin.math.roundToInt

/** Точка входа вкладки «Привычки». */
@Composable
fun HabitsScreen(viewModel: HabitsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail by viewModel.detailState.collectAsStateWithLifecycle()
    val detailRange by viewModel.detailRange.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Привычка") },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            HabitsUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is HabitsUiState.Content -> HabitsContent(
                content = current,
                contentPadding = innerPadding,
                onToggle = viewModel::toggleToday,
                onOpen = viewModel::openDetail,
            )
        }
    }

    detail?.let { detailState ->
        HabitDetailSheet(
            state = detailState,
            selectedRange = detailRange,
            onRangeSelected = viewModel::selectRange,
            onTargetChanged = { viewModel.updateTarget(detailState.habitId, it) },
            onResetCycle = { viewModel.resetCycle(detailState.habitId) },
            onDelete = { viewModel.deleteHabit(detailState.habitId) },
            onDismiss = viewModel::closeDetail,
        )
    }

    if (showAddDialog) {
        AddHabitDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, target, color ->
                viewModel.addHabit(title, target, color)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun HabitsContent(
    content: HabitsUiState.Content,
    contentPadding: PaddingValues,
    onToggle: (Long) -> Unit,
    onOpen: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text("Привычки", style = MaterialTheme.typography.headlineMedium)
        }
        item(key = "overview") {
            PaceOverviewCard(content.overview)
        }
        items(content.abandoned, key = { "abandoned-${it.habitId}" }) { habit ->
            AbandonedBanner(habit, onClick = { onOpen(habit.habitId) })
        }
        if (content.habits.isEmpty()) {
            item(key = "empty") { EmptyHabits() }
        }
        items(content.habits, key = { it.id }) { habit ->
            HabitCard(habit, onToggle = { onToggle(habit.id) }, onClick = { onOpen(habit.id) })
        }
    }
}

@Composable
private fun PaceOverviewCard(overview: PaceOverview) {
    val delta = overview.deltaPoints
    val (headline, tone) = when {
        overview.currentMonthRate == null -> "Добавьте привычку" to MaterialTheme.colorScheme.onSurface
        delta == null -> "Первый месяц наблюдений" to MaterialTheme.colorScheme.onSurface
        delta > 0 -> "+$delta% к прошлому месяцу" to MaterialTheme.colorScheme.primary
        delta < 0 -> "$delta% темп снизился" to MaterialTheme.colorScheme.error
        else -> "Темп как в прошлом месяце" to MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Обзор темпа", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(headline, style = MaterialTheme.typography.titleLarge, color = tone, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Этот месяц: ${formatRate(overview.currentMonthRate)} • прошлый: ${formatRate(overview.previousMonthRate)} нормы",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AbandonedBanner(habit: AbandonedHabit, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text(abandonedMessage(habit), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HabitCard(habit: HabitItemUi, onToggle: () -> Unit, onClick: () -> Unit) {
    val color = habitColor(habit.colorHex)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = habit.completedToday,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = habit.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val streakText = if (habit.currentStreak > 0) {
                    "Стрик: ${habit.currentStreak} ${daysWord(habit.currentStreak)} подряд"
                } else {
                    "Стрик не начат"
                }
                Text(
                    text = "$streakText • цикл ${habit.cycleProgress}/${habit.cycleLength}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WeekProgressRing(
                completed = habit.completedThisWeek,
                target = habit.targetDaysPerWeek,
                color = color,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(44.dp),
            )
        }
    }
}

@Composable
private fun EmptyHabits() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Пока нет привычек", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Нажмите «Привычка», чтобы добавить первую: например, «Прогулка» 5 дней в неделю.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddHabitDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, target: Int, colorHex: Long) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableFloatStateOf(5f) }
    var colorHex by rememberSaveable { mutableLongStateOf(HabitPalette.first()) }
    val trimmed = title.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая привычка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(MAX_TITLE_LENGTH) },
                    label = { Text("Название") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Норма: ${target.roundToInt()} ${daysWord(target.roundToInt())} в неделю",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = target,
                    onValueChange = { target = it },
                    valueRange = 1f..7f,
                    steps = 5,
                )
                Text("Цвет", style = MaterialTheme.typography.bodyMedium)
                ColorPicker(selected = colorHex, onSelected = { colorHex = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed, target.roundToInt(), colorHex) },
                enabled = trimmed.isNotEmpty(),
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun ColorPicker(selected: Long, onSelected: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HabitPalette.forEach { hex ->
            val isSelected = hex == selected
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(habitColor(hex), CircleShape)
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable { onSelected(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun formatRate(rate: Float?): String = rate?.let { "${(it * 100f).roundToInt()}%" } ?: "—"

private const val MAX_TITLE_LENGTH = 40
