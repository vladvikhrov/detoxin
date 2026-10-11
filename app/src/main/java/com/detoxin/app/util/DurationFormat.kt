package com.detoxin.app.util

/** «0 мин», «42 мин», «1 ч», «1 ч 30 мин» — подписи лимитов, тултипы и уведомления. */
fun formatDurationShort(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours == 0L -> "$minutes мин"
        minutes == 0L -> "$hours ч"
        else -> "$hours ч $minutes мин"
    }
}

/** «07:00» из минуты суток. */
fun formatMinuteOfDay(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
