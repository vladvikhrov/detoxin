package com.detoxin.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.detoxin.app.bedtime.BedtimeHabitEvaluator
import com.detoxin.app.bedtime.BedtimeNotifications
import com.detoxin.app.bedtime.BedtimeScheduler
import com.detoxin.app.ui.navigation.DetoxinApp
import com.detoxin.app.ui.theme.DetoxinTheme
import com.detoxin.app.util.ProcessScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Хост Compose-интерфейса: корневой каркас с нижней навигацией [DetoxinApp].
 *
 * При старте в фоне догоняет «Отбой»: каналы уведомлений, будильники (если система их сбросила)
 * и автопривычку «Лёг вовремя» за пропущенные утра.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) catchUpBedtime()
        enableEdgeToEdge()
        setContent {
            DetoxinTheme {
                DetoxinApp()
            }
        }
    }

    private fun catchUpBedtime() {
        val appContext = applicationContext
        ProcessScope.io.launch {
            try {
                BedtimeNotifications.ensureChannels(appContext)
                BedtimeScheduler.rescheduleFromStore(appContext)
                BedtimeHabitEvaluator.evaluate(appContext, System.currentTimeMillis(), ZoneId.systemDefault())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Bedtime catch-up failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
