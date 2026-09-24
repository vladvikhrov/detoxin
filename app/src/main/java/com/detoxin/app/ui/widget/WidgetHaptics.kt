package com.detoxin.app.ui.widget

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Короткий тактильный отклик на касание виджета: 40 мс, амплитуда по умолчанию. */
internal object WidgetHaptics {

    private const val TAG = "WidgetHaptics"
    private const val TICK_DURATION_MS = 40L

    fun tick(context: Context) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(TICK_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: SecurityException) {
            Log.w(TAG, "Vibration is not permitted", e)
        }
    }
}
