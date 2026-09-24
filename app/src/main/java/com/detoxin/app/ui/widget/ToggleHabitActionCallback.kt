package com.detoxin.app.ui.widget

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.detoxin.app.data.local.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Клик по виджету: отметка привычки за сегодня.
 *
 * Порядок: тактильный отклик сразу (мгновенная обратная связь) → атомарный toggle в Room
 * на [Dispatchers.IO] (одна транзакция `INSERT OR IGNORE` + `UPDATE` в [HabitDao.toggleHabit])
 * → только после коммита обновление состояния и UI виджета.
 */
class ToggleHabitActionCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appContext = context.applicationContext
        val habitId = parameters[HabitWidgetKeys.HabitIdParam]
            ?: getAppWidgetState(appContext, PreferencesGlanceStateDefinition, glanceId)[HabitWidgetKeys.HabitId]
            ?: return

        WidgetHaptics.tick(appContext)

        val today = LocalDate.now().toEpochDay()
        withContext(Dispatchers.IO) {
            try {
                DatabaseProvider.getDatabase(appContext).habitDao().toggleHabit(habitId, today)
            } catch (e: SQLiteConstraintException) {
                // Привычка удалена между отрисовкой и кликом: FK не даёт создать лог,
                // виджет после обновления покажет состояние «удалена».
                Log.w(TAG, "Habit $habitId no longer exists", e)
            }
        }

        updateAppWidgetState(appContext, glanceId) { prefs -> prefs[HabitWidgetKeys.RefreshDay] = today }
        HabitGlanceWidget().update(appContext, glanceId)
    }

    private companion object {
        const val TAG = "ToggleHabitAction"
    }
}
