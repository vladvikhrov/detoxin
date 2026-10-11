package com.detoxin.app.ui.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.detoxin.app.MainActivity
import com.detoxin.app.bedtime.BedtimeRepository
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.UsageStatsReader
import com.detoxin.app.util.formatDurationShort
import com.detoxin.app.util.formatMinuteOfDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Строка приложения в виджете «Сегодня». */
internal data class AppUsageRow(
    val name: String,
    val usedMs: Long,
    val limitMs: Long,
)

internal sealed interface ScreenTimeWidgetState {
    data object Loading : ScreenTimeWidgetState
    data object NoAccess : ScreenTimeWidgetState
    data class Ready(
        val apps: List<AppUsageRow>,
        /** «Отбой в 23:30» / «Отбой до 07:00»; `null` — режим выключен. */
        val bedtimeLine: String?,
    ) : ScreenTimeWidgetState
}

/**
 * Виджет «Сегодня» (по умолчанию 2×1): время TikTok и YouTube относительно дневных лимитов
 * и статус «Отбоя». Касание открывает приложение.
 *
 * Данные — системная статистика ([UsageStatsReader]) с тем же началом суток, что у лимитов
 * ([BedtimePolicy.budgetDayStart]): ночью после полуночи виджет показывает тот же счёт, по
 * которому работает блокировка. Реактивного источника нет, поэтому обновление запускается
 * явно ([refreshAll]) после каждой синхронизации статистики, изменения настроек «Отбоя» и в
 * полночь; `updatePeriodMillis` (30 минут) — страховка, когда служба не работает.
 * Binder-запрос к системе выполняется на Dispatchers.IO.
 */
class ScreenTimeWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        MidnightRefreshScheduler.schedule(appContext)
        val initialState = withContext(Dispatchers.IO) { load(appContext) }

        provideContent {
            val tick = currentState(HabitWidgetKeys.RefreshTick)
            val stateFlow = remember(tick) { observe(appContext) }
            val state by stateFlow.collectAsState(initial = initialState)
            GlanceTheme {
                ScreenTimeContent(state)
            }
        }
    }

    companion object {
        private const val TAG = "ScreenTimeWidget"
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Перечитать статистику во всех экземплярах. Безопасно вызывать часто: без экземпляров — no-op. */
        suspend fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            try {
                val glanceIds = GlanceAppWidgetManager(appContext).getGlanceIds(ScreenTimeWidget::class.java)
                if (glanceIds.isEmpty()) return
                val widget = ScreenTimeWidget()
                val now = System.currentTimeMillis()
                for (glanceId in glanceIds) {
                    updateAppWidgetState(appContext, glanceId) { prefs -> prefs[HabitWidgetKeys.RefreshTick] = now }
                    widget.update(appContext, glanceId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Screen time widget refresh failed", e)
            }
        }

        private fun observe(context: Context): Flow<ScreenTimeWidgetState> =
            flow { emit(load(context)) }.flowOn(Dispatchers.IO)

        private suspend fun load(context: Context): ScreenTimeWidgetState {
            if (!UsageStatsReader.hasAccess(context)) return ScreenTimeWidgetState.NoAccess
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val bedtime = BedtimeRepository.current(context).effectiveAt(now)
            val dayStart = BedtimePolicy.budgetDayStart(now, zone, bedtime)
            val sessions = UsageStatsReader.readSessions(context, dayStart, now, TrackedApp.packageNames)
            val apps = TrackedApp.entries.map { app ->
                AppUsageRow(
                    name = app.displayName,
                    usedMs = sessions.filter { it.packageName == app.packageName }.sumOf { it.endMs - it.startMs },
                    limitMs = app.dailyLimitMs,
                )
            }
            val window = BedtimePolicy.currentWindow(now, zone, bedtime)
            val bedtimeLine = when {
                !bedtime.enabled || !bedtime.hasWindow -> null
                window != null -> "Отбой до " + Instant.ofEpochMilli(window.endWall).atZone(zone).format(CLOCK)
                else -> "Отбой в " + formatMinuteOfDay(bedtime.startMinute)
            }
            return ScreenTimeWidgetState.Ready(apps, bedtimeLine)
        }
    }
}

class ScreenTimeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScreenTimeWidget()

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        MidnightRefreshScheduler.cancelIfNoWidgets(context)
    }
}

@Composable
private fun ScreenTimeContent(state: ScreenTimeWidgetState) {
    val colors = GlanceTheme.colors
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            ScreenTimeWidgetState.Loading -> Unit
            ScreenTimeWidgetState.NoAccess -> Text(
                text = "Нет доступа к истории использования. Откройте Detoxin, чтобы выдать его.",
                maxLines = 3,
                style = TextStyle(color = colors.error, fontSize = 11.sp),
            )
            is ScreenTimeWidgetState.Ready -> {
                state.apps.forEach { app -> UsageRow(app) }
                if (state.bedtimeLine != null) {
                    Text(
                        text = state.bedtimeLine,
                        maxLines = 1,
                        style = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageRow(app: AppUsageRow) {
    val colors = GlanceTheme.colors
    val over = app.usedMs >= app.limitMs
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = app.name,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            text = "${formatDurationShort(app.usedMs)} / ${formatDurationShort(app.limitMs)}",
            maxLines = 1,
            style = TextStyle(color = if (over) colors.error else colors.onSurfaceVariant, fontSize = 11.sp),
        )
    }
    Spacer(GlanceModifier.height(2.dp))
    LinearProgressIndicator(
        progress = (app.usedMs.toFloat() / app.limitMs).coerceIn(0f, 1f),
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        color = if (over) colors.error else colors.primary,
        backgroundColor = colors.surfaceVariant,
    )
    Spacer(GlanceModifier.height(4.dp))
}
