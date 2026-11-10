package com.detoxin.app.core

/**
 * Приложения с полной блокировкой: запуск сразу возвращает на главный экран, а экран их
 * установки в магазине приложений или установщике пакетов закрывается.
 *
 * Блокировка каждого приложения включена по умолчанию; выключить её можно только на экране
 * «Защита» после PIN-кода и контрольной фразы. С выключенной блокировкой TikTok и YouTube
 * возвращаются к обычным лимитам [TrackedApp].
 *
 * @property key стабильный ключ для хранения настройки.
 * @property packages все известные имена пакета (региональные и облегчённые сборки).
 * @property storeTitles названия в нижнем регистре, по которым приложение узнаётся на экране
 * установки.
 * @property titleExclusions другие приложения, чьё название начинается так же (YouTube Music).
 */
enum class HardBlockedApp(
    val key: String,
    val displayName: String,
    val packages: Set<String>,
    val storeTitles: Set<String>,
    val titleExclusions: Set<String> = emptySet(),
) {
    TIKTOK(
        key = "tiktok",
        displayName = "TikTok",
        packages = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.aweme",
        ),
        storeTitles = setOf("tiktok", "tiktok lite", "тикток"),
        titleExclusions = setOf("tiktok studio", "tiktok seller", "tiktok shop seller"),
    ),
    YOUTUBE(
        key = "youtube",
        displayName = "YouTube",
        packages = setOf("com.google.android.youtube"),
        storeTitles = setOf("youtube", "ютуб"),
        titleExclusions = setOf(
            "youtube music", "youtube kids", "youtube studio", "youtube tv", "youtube детям",
            "youtube create", "youtube go",
        ),
    ),
    YANDEX_MUSIC(
        key = "yandex_music",
        displayName = "Яндекс Музыка",
        packages = setOf("ru.yandex.music"),
        storeTitles = setOf("яндекс музыка", "яндекс.музыка", "yandex music", "yandex.music"),
    );

    companion object {
        private val byPackage: Map<String, HardBlockedApp> =
            entries.flatMap { app -> app.packages.map { it to app } }.toMap()

        val packageNames: Set<String> = byPackage.keys

        fun fromPackage(packageName: String?): HardBlockedApp? = packageName?.let(byPackage::get)

        fun fromKey(key: String): HardBlockedApp? = entries.firstOrNull { it.key == key }
    }
}
