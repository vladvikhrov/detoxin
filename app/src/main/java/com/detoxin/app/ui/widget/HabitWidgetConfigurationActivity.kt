package com.detoxin.app.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.dao.HabitDao
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.ui.theme.DetoxinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Экран привязки виджета к привычке. Запускается лаунчером при размещении виджета,
 * при переконфигурации (`widgetFeatures="reconfigurable"`) и по касанию виджета в
 * состояниях «не настроен» / «привычка удалена».
 *
 * Контракт APPWIDGET_CONFIGURE: результат по умолчанию RESULT_CANCELED (лаунчер уберёт
 * виджет, если пользователь закроет экран), RESULT_OK с EXTRA_APPWIDGET_ID — после привязки.
 * Все обращения к Room и DataStore — в корутинах на Dispatchers.IO.
 */
class HabitWidgetConfigurationActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isBinding = false

    private lateinit var habitDao: HabitDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        habitDao = DatabaseProvider.getDatabase(applicationContext).habitDao()

        enableEdgeToEdge()
        setContent {
            DetoxinTheme {
                val habits by produceState<List<Habit>?>(initialValue = null) {
                    habitDao.observeHabits()
                        .flowOn(Dispatchers.IO)
                        .collect { value = it }
                }
                HabitPickerScreen(
                    habits = habits,
                    onHabitSelected = ::bindHabit,
                    onCreateHabit = ::createAndBindHabit,
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun createAndBindHabit(title: String) {
        if (isBinding) return
        isBinding = true
        lifecycleScope.launch {
            val habit = withContext(Dispatchers.IO) {
                val draft = Habit(
                    title = title,
                    targetDaysPerWeek = DEFAULT_TARGET_DAYS,
                    colorHex = DEFAULT_COLOR,
                    createdAt = System.currentTimeMillis(),
                )
                draft.copy(id = habitDao.insertHabit(draft))
            }
            completeBinding(habit.id)
        }
    }

    private fun bindHabit(habit: Habit) {
        if (isBinding) return
        isBinding = true
        lifecycleScope.launch { completeBinding(habit.id) }
    }

    private suspend fun completeBinding(habitId: Long) {
        try {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            val today = LocalDate.now().toEpochDay()
            withContext(Dispatchers.IO) {
                updateAppWidgetState(applicationContext, glanceId) { prefs ->
                    prefs[HabitWidgetKeys.HabitId] = habitId
                    prefs[HabitWidgetKeys.RefreshDay] = today
                }
            }
            HabitGlanceWidget().update(applicationContext, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        } catch (e: IllegalArgumentException) {
            // Экземпляр виджета удалён с рабочего стола, пока открыт экран.
            Log.w(TAG, "App widget $appWidgetId is not bound to HabitGlanceWidget", e)
        } finally {
            finish()
        }
    }

    private companion object {
        const val TAG = "HabitWidgetConfig"
        const val DEFAULT_TARGET_DAYS = 7
        const val DEFAULT_COLOR = 0xFF1B7F4BL
    }
}

@Composable
private fun HabitPickerScreen(
    habits: List<Habit>?,
    onHabitSelected: (Habit) -> Unit,
    onCreateHabit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Привычка для виджета", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Касание виджета будет отмечать выбранную привычку за сегодня.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    habits == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    habits.isEmpty() -> Text(
                        text = "Привычек пока нет. Создайте первую ниже — она сразу привяжется к виджету.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(habits, key = { it.id }) { habit ->
                            HabitRow(habit = habit, onClick = { onHabitSelected(habit) })
                            HorizontalDivider()
                        }
                    }
                }
            }

            NewHabitField(onCreateHabit)

            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text("Отмена")
            }
        }
    }
}

@Composable
private fun HabitRow(habit: Habit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(habit.title) },
        supportingContent = { Text("Цель: ${habit.targetDaysPerWeek} дн. в неделю") },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(Color(habit.colorHex), CircleShape),
            )
        },
    )
}

@Composable
private fun NewHabitField(onCreateHabit: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    val trimmed = remember(title) { title.trim() }
    val submit = { if (trimmed.isNotEmpty()) onCreateHabit(trimmed) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(MAX_TITLE_LENGTH) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Новая привычка") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Button(onClick = submit, enabled = trimmed.isNotEmpty()) {
            Text("Создать")
        }
    }
}

private const val MAX_TITLE_LENGTH = 40
