package com.detoxin.app.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity as startActivityAction
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.detoxin.app.MainActivity
import com.detoxin.app.R
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.dao.HabitDao
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.entity.HabitLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** День в ряду виджета «Неделя привычки». */
internal data class WeekWidgetDay(
    val epochDay: Long,
    val label: String,
    val isCompleted: Boolean,
    val isToday: Boolean,
)

internal sealed interface HabitWeekWidgetState {
    data object Loading : HabitWeekWidgetState
    data object NotConfigured : HabitWeekWidgetState
    data object Deleted : HabitWeekWidgetState
    data class Ready(
        val habitId: Long,
        val title: String,
        val colorHex: Long,
        val days: List<WeekWidgetDay>,
    ) : HabitWeekWidgetState
}

/**
 * Виджет «Неделя привычки» (по умолчанию 3×1, растягивается): название и 7 последних дней.
 * Касание дня отмечает или снимает отметку за этот день ([ToggleHabitDayActionCallback]),
 * касание названия открывает приложение.
 *
 * Как и 1×1-виджет, хранит привязку `habitId` в Glance DataStore экземпляра, настраивается
 * тем же [HabitWidgetConfigurationActivity] и внутри `provideContent` подписан на Flow Room:
 * отметка из приложения или другого виджета перерисовывает его без явного update.
 */
class HabitWeekWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        val habitDao = DatabaseProvider.getDatabase(appContext).habitDao()
        val appWidgetId = GlanceAppWidgetManager(appContext).getAppWidgetId(id)
        val configureIntent = Intent(appContext, HabitWidgetConfigurationActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val locale = Locale.getDefault()

        MidnightRefreshScheduler.schedule(appContext)

        val initialHabitId = getAppWidgetState(appContext, PreferencesGlanceStateDefinition, id)[HabitWidgetKeys.HabitId]
        val initialDay = LocalDate.now()
        val initialState = withContext(Dispatchers.IO) {
            observeState(habitDao, initialHabitId, initialDay, locale).first()
        }

        provideContent {
            val habitId = currentState(HabitWidgetKeys.HabitId)
            val refreshDay = currentState(HabitWidgetKeys.RefreshDay)
            val today = remember(refreshDay) { LocalDate.now() }
            val stateFlow = remember(habitId, today) { observeState(habitDao, habitId, today, locale) }
            val fallback = if (habitId == initialHabitId && today == initialDay) initialState else HabitWeekWidgetState.Loading
            val state by stateFlow.collectAsState(initial = fallback)

            GlanceTheme {
                HabitWeekContent(
                    state = state,
                    configureAction = actionStartActivity(configureIntent),
                    openAppAction = startActivityAction<MainActivity>(),
                )
            }
        }
    }

    companion object {
        /** Виджет показывает ровно столько дней, сколько можно отметить касанием. */
        const val DAYS = 7

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun observeState(dao: HabitDao, habitId: Long?, today: LocalDate, locale: Locale): Flow<HabitWeekWidgetState> {
            if (habitId == null) return flowOf(HabitWeekWidgetState.NotConfigured)
            val from = today.minusDays(DAYS - 1L)
            return dao.observeHabit(habitId).flatMapLatest { habit ->
                if (habit == null) {
                    flowOf(HabitWeekWidgetState.Deleted)
                } else {
                    dao.getLogsForHabit(habitId, from.toEpochDay(), today.toEpochDay())
                        .map { logs -> readyState(habit, logs, today, locale) }
                }
            }
        }

        private fun readyState(habit: Habit, logs: List<HabitLog>, today: LocalDate, locale: Locale): HabitWeekWidgetState {
            val completed = logs.filter { it.isCompleted }.map { it.dateEpochDay }.toHashSet()
            val days = (DAYS - 1 downTo 0).map { offset ->
                val date = today.minusDays(offset.toLong())
                WeekWidgetDay(
                    epochDay = date.toEpochDay(),
                    label = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale),
                    isCompleted = date.toEpochDay() in completed,
                    isToday = offset == 0,
                )
            }
            return HabitWeekWidgetState.Ready(habit.id, habit.title, habit.colorHex, days)
        }
    }
}

class HabitWeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitWeekWidget()

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        MidnightRefreshScheduler.cancelIfNoWidgets(context)
    }
}

@Composable
private fun HabitWeekContent(state: HabitWeekWidgetState, configureAction: Action, openAppAction: Action) {
    val colors = GlanceTheme.colors
    val ready = state as? HabitWeekWidgetState.Ready
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .let { if (ready == null) it.clickable(configureAction) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = when (state) {
            is HabitWeekWidgetState.Ready -> state.title
            HabitWeekWidgetState.NotConfigured -> "Коснитесь, чтобы выбрать привычку"
            HabitWeekWidgetState.Deleted -> "Привычка удалена — выберите другую"
            HabitWeekWidgetState.Loading -> ""
        }
        Text(
            text = title,
            maxLines = 1,
            modifier = GlanceModifier.fillMaxWidth().clickable(if (ready == null) configureAction else openAppAction),
            style = TextStyle(
                color = if (state is HabitWeekWidgetState.Deleted) colors.error else colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        if (ready != null) {
            Spacer(GlanceModifier.height(4.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val color = ColorProvider(Color(ready.colorHex))
                ready.days.forEach { day ->
                    DayCell(habitId = ready.habitId, day = day, color = color, modifier = GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun DayCell(habitId: Long, day: WeekWidgetDay, color: ColorProvider, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    val status = if (day.isCompleted) "выполнено" else "не выполнено"
    Column(
        modifier = modifier
            .clickable(
                actionRunCallback<ToggleHabitDayActionCallback>(
                    actionParametersOf(
                        HabitWidgetKeys.HabitIdParam to habitId,
                        HabitWidgetKeys.EpochDayParam to day.epochDay,
                    )
                )
            )
            .semantics { contentDescription = "${day.label}: $status" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = day.label,
            maxLines = 1,
            style = TextStyle(
                color = if (day.isToday) colors.onSurface else colors.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
        Box(modifier = GlanceModifier.size(24.dp), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(if (day.isCompleted) R.drawable.widget_circle_filled else R.drawable.widget_circle_outline),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(color),
            )
            if (day.isCompleted) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_check),
                    contentDescription = null,
                    modifier = GlanceModifier.size(14.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                )
            }
        }
    }
}
