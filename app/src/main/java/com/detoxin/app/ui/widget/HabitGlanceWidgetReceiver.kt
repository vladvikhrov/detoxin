package com.detoxin.app.ui.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Ресивер виджета. Помимо стандартных событий AppWidget обрабатывает смену дня
 * (будильник [MidnightRefreshScheduler], ручная смена времени и часового пояса):
 * работа выполняется асинхронно под `goAsync()`, не блокируя главный поток.
 */
class HabitGlanceWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = HabitGlanceWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in dayChangeActions) {
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    HabitGlanceWidget.refreshAllForNewDay(appContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh habit widgets for the new day", e)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        MidnightRefreshScheduler.cancelIfNoWidgets(context)
    }

    private companion object {
        const val TAG = "HabitWidgetReceiver"

        val dayChangeActions = setOf(
            MidnightRefreshScheduler.ACTION_DAY_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
