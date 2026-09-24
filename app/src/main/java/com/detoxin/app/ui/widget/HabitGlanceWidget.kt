package com.detoxin.app.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.detoxin.app.R
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.dao.HabitDao
import com.detoxin.app.data.local.entity.Habit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Отображаемое состояние виджета. */
internal sealed interface HabitWidgetState {
    data object Loading : HabitWidgetState
    data object NotConfigured : HabitWidgetState
    data object Deleted : HabitWidgetState
    data class Ready(
        val habitId: Long,
        val title: String,
        val colorHex: Long,
        val isCompleted: Boolean,
        val cycleProgress: Int,
        val cycleLength: Int,
    ) : HabitWidgetState
}

/**
 * Виджет привычки 1×1.
 *
 * Данные: привязка `habitId` хранится в Glance DataStore экземпляра
 * ([PreferencesGlanceStateDefinition]). Внутри `provideContent` виджет подписан на Flow Room
 * (привычка + отметка за день), поэтому пока Glance-сессия жива, любое изменение в базе —
 * из виджета, приложения или другого экземпляра — перерисовывает UI без явного update.
 * Начальный снимок читается до `provideContent`, чтобы первый кадр сразу был с данными.
 *
 * UI использует только компоненты Glance (RemoteViews): никаких foundation/material3.
 * Кольцо цикла рисуется процедурно в Bitmap ([HabitRingBitmapRenderer]).
 */
class HabitGlanceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        val habitDao = DatabaseProvider.getDatabase(appContext).habitDao()
        val appWidgetId = GlanceAppWidgetManager(appContext).getAppWidgetId(id)
        val configureIntent = Intent(appContext, HabitWidgetConfigurationActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        MidnightRefreshScheduler.schedule(appContext)

        val initialPrefs = getAppWidgetState(appContext, PreferencesGlanceStateDefinition, id)
        val initialHabitId = initialPrefs[HabitWidgetKeys.HabitId]
        val initialDay = LocalDate.now().toEpochDay()
        val initialState = withContext(Dispatchers.IO) { loadSnapshot(habitDao, initialHabitId, initialDay) }

        provideContent {
            val habitId = currentState(HabitWidgetKeys.HabitId)
            val refreshDay = currentState(HabitWidgetKeys.RefreshDay)
            val day = remember(refreshDay) { LocalDate.now().toEpochDay() }
            val stateFlow = remember(habitId, day) { observeState(habitDao, habitId, day) }
            val fallback = if (habitId == initialHabitId && day == initialDay) initialState else HabitWidgetState.Loading
            val state by stateFlow.collectAsState(initial = fallback)

            GlanceTheme {
                HabitWidgetContent(state = state, configureAction = actionStartActivity(configureIntent))
            }
        }
    }

    companion object {
        /** Сдвигает все экземпляры на новый день (полночь, смена времени или часового пояса). */
        suspend fun refreshAllForNewDay(context: Context) {
            val today = LocalDate.now().toEpochDay()
            val widget = HabitGlanceWidget()
            val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(HabitGlanceWidget::class.java)
            for (glanceId in glanceIds) {
                updateAppWidgetState(context, glanceId) { prefs -> prefs[HabitWidgetKeys.RefreshDay] = today }
                widget.update(context, glanceId)
            }
            if (glanceIds.isNotEmpty()) MidnightRefreshScheduler.schedule(context)
        }

        private suspend fun loadSnapshot(dao: HabitDao, habitId: Long?, day: Long): HabitWidgetState {
            if (habitId == null) return HabitWidgetState.NotConfigured
            val habit = dao.getHabitById(habitId) ?: return HabitWidgetState.Deleted
            return readyState(
                habit = habit,
                completed = dao.getCompletion(habitId, day) == true,
                cycleCount = dao.getCompletionCount(habitId, habit.cycleResetEpochDay, Long.MAX_VALUE),
            )
        }

        /**
         * Реактивное состояние: смена привычки (в т.ч. сброс цикла) переключает подписку на
         * счётчик выполнений с новой точки отсчёта; отметка за день и счётчик обновляются Room.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun observeState(dao: HabitDao, habitId: Long?, day: Long): Flow<HabitWidgetState> {
            if (habitId == null) return flowOf(HabitWidgetState.NotConfigured)
            return dao.observeHabit(habitId).flatMapLatest { habit ->
                if (habit == null) {
                    flowOf(HabitWidgetState.Deleted)
                } else {
                    combine(
                        dao.observeCompletion(habitId, day),
                        dao.observeCompletionCount(habitId, habit.cycleResetEpochDay, Long.MAX_VALUE),
                    ) { completed, cycleCount ->
                        readyState(habit, completed == true, cycleCount)
                    }
                }
            }
        }

        private fun readyState(habit: Habit, completed: Boolean, cycleCount: Int) = HabitWidgetState.Ready(
            habitId = habit.id,
            title = habit.title,
            colorHex = habit.colorHex,
            isCompleted = completed,
            cycleProgress = cycleCount.coerceAtMost(habit.cycleLength),
            cycleLength = habit.cycleLength,
        )
    }
}

@Composable
private fun HabitWidgetContent(state: HabitWidgetState, configureAction: Action) {
    val colors = GlanceTheme.colors
    val clickAction: Action = when (state) {
        is HabitWidgetState.Ready -> actionRunCallback<ToggleHabitActionCallback>(
            actionParametersOf(HabitWidgetKeys.HabitIdParam to state.habitId)
        )
        HabitWidgetState.Loading -> actionRunCallback<ToggleHabitActionCallback>()
        HabitWidgetState.NotConfigured, HabitWidgetState.Deleted -> configureAction
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(if (state is HabitWidgetState.Ready) RingFaceBackground else colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(4.dp)
            .clickable(clickAction),
        contentAlignment = Alignment.Center,
    ) {
        if (state is HabitWidgetState.Ready) {
            CycleRingContent(state)
            return@Box
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is HabitWidgetState.Ready -> Unit
                HabitWidgetState.NotConfigured -> Indicator(
                    ring = R.drawable.widget_circle_outline,
                    ringColor = colors.outline,
                    icon = R.drawable.ic_widget_tune,
                    iconColor = colors.onSurfaceVariant,
                    description = "Выбрать привычку",
                )
                HabitWidgetState.Deleted -> Indicator(
                    ring = R.drawable.widget_circle_filled,
                    ringColor = colors.errorContainer,
                    icon = R.drawable.ic_widget_warning,
                    iconColor = colors.onErrorContainer,
                    description = "Привычка удалена, выберите другую",
                )
                HabitWidgetState.Loading -> Indicator(
                    ring = R.drawable.widget_circle_outline,
                    ringColor = colors.outline,
                    icon = null,
                    iconColor = colors.outline,
                    description = null,
                )
            }

            val label = when (state) {
                is HabitWidgetState.Ready -> ""
                HabitWidgetState.NotConfigured -> "Настроить"
                HabitWidgetState.Deleted -> "Удалена"
                HabitWidgetState.Loading -> ""
            }
            if (label.isNotEmpty()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = label,
                    maxLines = 1,
                    style = TextStyle(
                        color = if (state is HabitWidgetState.Deleted) colors.error else colors.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/**
 * Кольцо цикла на весь виджет и название под ним. Bitmap пересоздаётся только при смене
 * входных данных (`remember` по ключам), предыдущий экземпляр утилизируется в `onDispose`:
 * к этому моменту новая композиция уже не ссылается на него, а старые RemoteViews уже
 * сериализованы и отправлены лаунчеру.
 */
/**
 * «Циферблат» кольца всегда тёмный: приглушённые сегменты #2A313A должны контрастировать с
 * подложкой, а системный фон виджета (например, на Honor) почти совпадает с ними по тону.
 */
private val RingFaceBackground = ColorProvider(Color(0xFF101418))
private val RingFaceText = ColorProvider(Color(0xFFE3E7EC))

@Composable
private fun CycleRingContent(state: HabitWidgetState.Ready) {
    val bitmap = remember(state.cycleProgress, state.cycleLength, state.isCompleted, state.colorHex) {
        HabitRingBitmapRenderer.render(
            progress = state.cycleProgress,
            cycleLength = state.cycleLength,
            completedToday = state.isCompleted,
            colorArgb = state.colorHex.toInt(),
        )
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap.recycle() }
    }
    val status = if (state.isCompleted) "выполнено" else "не выполнено"
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "${state.title}: $status, день цикла ${state.cycleProgress} из ${state.cycleLength}",
            modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = state.title,
            maxLines = 1,
            style = TextStyle(
                color = RingFaceText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun Indicator(
    ring: Int,
    ringColor: ColorProvider,
    icon: Int?,
    iconColor: ColorProvider,
    description: String?,
) {
    Box(
        modifier = GlanceModifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(ring),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(ringColor),
        )
        if (icon != null) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = description,
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(iconColor),
            )
        }
    }
}
