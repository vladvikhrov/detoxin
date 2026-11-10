package com.detoxin.app.ui.widget

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Касание дня в виджете «Неделя привычки»: toggle отметки за этот день.
 *
 * Порядок тот же, что у [ToggleHabitActionCallback]: отклик сразу → атомарный toggle в Room на IO
 * → обновление виджетов только после коммита. Дни вне последней недели (виджет мог не
 * перерисоваться после полуночи) и будущие дни игнорируются. Перерисовываются все виджеты
 * привычек: 1×1-виджет той же привычки показывает прогресс цикла, в который входит этот день.
 */
class ToggleHabitDayActionCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appContext = context.applicationContext
        val habitId = parameters[HabitWidgetKeys.HabitIdParam] ?: return
        val epochDay = parameters[HabitWidgetKeys.EpochDayParam] ?: return
        val today = LocalDate.now().toEpochDay()
        if (epochDay > today || epochDay <= today - HabitWeekWidget.DAYS) return

        WidgetHaptics.tick(appContext)

        withContext(Dispatchers.IO) {
            try {
                DatabaseProvider.getDatabase(appContext).habitDao()
                    .toggleHabitStep(habitId, epochDay, Habit.isEveningOpen(epochDay, java.time.LocalDateTime.now()))
            } catch (e: SQLiteConstraintException) {
                Log.w(TAG, "Habit $habitId no longer exists", e)
            }
        }
        HabitGlanceWidget.refreshAll(appContext)
    }

    private companion object {
        const val TAG = "ToggleHabitDayAction"
    }
}
