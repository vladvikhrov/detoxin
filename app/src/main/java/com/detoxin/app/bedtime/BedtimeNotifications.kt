package com.detoxin.app.bedtime

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.detoxin.app.MainActivity
import com.detoxin.app.R
import com.detoxin.app.core.TrackedApp
import com.detoxin.app.usage.BedtimeSettings
import com.detoxin.app.util.formatDurationShort
import com.detoxin.app.util.formatMinuteOfDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Уведомления режима «Отбой». Два канала: тихое напоминание перед сном ([CHANNEL_BEDTIME]) и
 * итог периода, когда защита была выключена ([CHANNEL_PROTECTION]).
 *
 * На Android 13+ без разрешения POST_NOTIFICATIONS уведомления молча не показываются —
 * разрешение запрашивается на экране настроек «Отбоя» при включении режима.
 */
object BedtimeNotifications {

    const val CHANNEL_BEDTIME = "bedtime"
    const val CHANNEL_PROTECTION = "protection_report"

    private const val TAG = "BedtimeNotifications"

    private const val ID_REMINDER = 7_301
    private const val ID_GAP_REPORT = 7_302
    private const val REQUEST_OPEN_APP = 7_310
    private const val DAY_MS = 24L * 60L * 60L * 1_000L

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_BEDTIME, "Отбой", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Напоминание за 30 минут до начала режима «Отбой»"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROTECTION, "Отключение защиты", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Итог периода, когда служба Detoxin была выключена"
            }
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showReminder(context: Context, settings: BedtimeSettings) {
        val text = "В ${formatMinuteOfDay(settings.startMinute)} TikTok и YouTube закроются до " +
            "${formatMinuteOfDay(settings.wakeMinute)}. Самое время отложить телефон."
        post(
            context = context,
            id = ID_REMINDER,
            builder = NotificationCompat.Builder(context, CHANNEL_BEDTIME)
                .setSmallIcon(R.drawable.ic_bedtime)
                .setContentTitle("Через 30 минут отбой")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setTimeoutAfter(BedtimeScheduler.REMINDER_TIMEOUT_MS),
        )
    }

    /** Итог выключения защиты: когда и сколько времени ушло на TikTok и YouTube. */
    fun showGapReport(
        context: Context,
        startedAtWall: Long,
        endedAtWall: Long,
        usage: Map<TrackedApp, Long>,
        zone: ZoneId,
    ) {
        val locale = Locale.getDefault()
        val pattern = if (endedAtWall - startedAtWall >= DAY_MS) "d MMM HH:mm" else "HH:mm"
        val formatter = DateTimeFormatter.ofPattern(pattern, locale)
        val from = Instant.ofEpochMilli(startedAtWall).atZone(zone).format(formatter)
        val to = Instant.ofEpochMilli(endedAtWall).atZone(zone).format(formatter)
        val used = usage.filterValues { it >= MIN_REPORTED_USAGE_MS }
        val text = if (used.isEmpty()) {
            "За это время TikTok и YouTube не открывались."
        } else {
            "За это время " + used.entries.joinToString(", ") { (app, ms) -> "${app.displayName}: ${formatDurationShort(ms)}" } + "."
        }
        post(
            context = context,
            id = ID_GAP_REPORT,
            builder = NotificationCompat.Builder(context, CHANNEL_PROTECTION)
                .setSmallIcon(R.drawable.ic_widget_warning)
                .setContentTitle("Защита была выключена $from–$to")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS),
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canPost(context)) return
        ensureChannels(context)
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = builder.setContentIntent(openApp).setAutoCancel(true).build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Разрешение отозвано между проверкой и показом — уведомление просто не показывается.
            Log.w(TAG, "Notification permission revoked before posting", e)
        }
    }

    /** Короче минуты в отчёте не показываем: это секунды под экраном блокировки. */
    private const val MIN_REPORTED_USAGE_MS = 60_000L
}
