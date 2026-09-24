package com.detoxin.app.core

/**
 * Приложения с учётом экранного времени и суточным лимитом.
 * Единый источник правды для сервиса (трекинг и блокировка) и экрана аналитики (линия лимита).
 *
 * @property limitLabel подпись лимита для оверлея: «Дневной лимит TikTok (1 ч) исчерпан».
 */
enum class TrackedApp(
    val packageName: String,
    val displayName: String,
    val dailyLimitMs: Long,
    val limitLabel: String,
) {
    TIKTOK(
        packageName = "com.zhiliaoapp.musically",
        displayName = "TikTok",
        dailyLimitMs = 60L * 60L * 1_000L,
        limitLabel = "1 ч",
    ),
    YOUTUBE(
        packageName = "com.google.android.youtube",
        displayName = "YouTube",
        dailyLimitMs = 90L * 60L * 1_000L,
        limitLabel = "1.5 ч",
    );

    companion object {
        private val byPackage: Map<String, TrackedApp> = entries.associateBy { it.packageName }

        val packageNames: List<String> = entries.map { it.packageName }

        /** Суммарный суточный бюджет всех отслеживаемых приложений (2 ч 30 мин). */
        val combinedDailyLimitMs: Long = entries.sumOf { it.dailyLimitMs }

        fun fromPackage(packageName: String?): TrackedApp? = packageName?.let(byPackage::get)
    }
}
