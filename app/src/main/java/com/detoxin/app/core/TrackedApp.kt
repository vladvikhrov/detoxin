package com.detoxin.app.core

/**
 * Приложения с учётом экранного времени и лимитами.
 * Единый источник правды для сервиса (блокировка), синхронизации статистики и экрана аналитики.
 *
 * @property hourlyLimitMs лимит на календарный час (например, 14:00–15:00); `null` — без часового лимита.
 * @property limitLabel / [hourlyLimitLabel] — подписи для экрана блокировки.
 */
enum class TrackedApp(
    val packageName: String,
    val displayName: String,
    val dailyLimitMs: Long,
    val limitLabel: String,
    val hourlyLimitMs: Long?,
    val hourlyLimitLabel: String?,
) {
    TIKTOK(
        packageName = "com.zhiliaoapp.musically",
        displayName = "TikTok",
        dailyLimitMs = 60L * 60L * 1_000L,
        limitLabel = "1 ч",
        hourlyLimitMs = 15L * 60L * 1_000L,
        hourlyLimitLabel = "15 мин",
    ),
    YOUTUBE(
        packageName = "com.google.android.youtube",
        displayName = "YouTube",
        dailyLimitMs = 90L * 60L * 1_000L,
        limitLabel = "1.5 ч",
        hourlyLimitMs = null,
        hourlyLimitLabel = null,
    );

    companion object {
        private val byPackage: Map<String, TrackedApp> = entries.associateBy { it.packageName }

        val packageNames: Set<String> = entries.map { it.packageName }.toSet()

        /** Суммарный суточный бюджет всех отслеживаемых приложений (2 ч 30 мин). */
        val combinedDailyLimitMs: Long = entries.sumOf { it.dailyLimitMs }

        fun fromPackage(packageName: String?): TrackedApp? = packageName?.let(byPackage::get)
    }
}
