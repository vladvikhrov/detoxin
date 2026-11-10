package com.detoxin.app.service.detector

import com.detoxin.app.core.HardBlockedApp

/**
 * Экран установки заблокированного приложения: страница в магазине (Google Play, RuStore,
 * AppGallery, магазин Honor) или диалог системного установщика пакетов.
 *
 * Работает на [NodeSnapshot], без живых нод. Срабатывает, когда на экране одновременно есть:
 *  1. название заблокированного приложения — подпись совпадает с названием целиком или
 *     продолжается разделителем («TikTok: видео, музыка», «YouTube — смотрите»); названия
 *     родственных приложений (YouTube Music, YouTube Kids) исключены;
 *  2. кнопка установки («Установить», «Install», «Включить» для отключённого приложения).
 *
 * В диалоге установщика пакетов кнопка установки есть всегда, а название может стоять внутри
 * фразы («Установить приложение TikTok?»), поэтому там название ищется как отдельное слово.
 */
internal object InstallScreenDetector {

    val storePackages: Set<String> = setOf(
        "com.android.vending",
        "ru.vk.store",
        "com.huawei.appmarket",
        "com.hihonor.appmarket",
        "com.xiaomi.mipicks",
        "com.sec.android.app.samsungapps",
        "com.aurora.store",
        "org.fdroid.fdroid",
    )

    val installerPackages: Set<String> = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.hihonor.packageinstaller",
        "com.huawei.packageinstaller",
    )

    val packages: Set<String> = storePackages + installerPackages

    private val installKeywords = setOf(
        "установить", "install", "включить", "enable", "получить", "скачать", "обновить", "update",
    )

    private val titleSeparators = charArrayOf(':', '-', '—', '–', ',', '|', '(')

    /** Строки для системного поиска по тексту: названия приложений и подписи кнопки установки. */
    fun anchorQueries(apps: Collection<HardBlockedApp>): List<String> =
        apps.flatMap { it.storeTitles } + listOf("Установить", "Install", "Включить", "Enable")

    /**
     * @param apps приложения с включённой полной блокировкой.
     * @return приложение, экран установки которого открыт, или `null`.
     */
    fun detect(packageName: String, nodes: List<NodeSnapshot>, apps: Collection<HardBlockedApp>): HardBlockedApp? {
        if (apps.isEmpty()) return null
        val labels = nodes.flatMap { it.normalizedLabels }
        if (labels.isEmpty()) return null
        val isInstaller = packageName in installerPackages
        if (!isInstaller && labels.none { it in installKeywords }) return null
        return apps.firstOrNull { app ->
            labels.any { label -> if (isInstaller) mentions(label, app) else isTitleOf(label, app) }
        }
    }

    /** Подпись — название приложения: целиком или с продолжением после разделителя. */
    internal fun isTitleOf(label: String, app: HardBlockedApp): Boolean {
        if (app.titleExclusions.any { label.startsWith(it) }) return false
        return app.storeTitles.any { title ->
            if (!label.startsWith(title)) return@any false
            val rest = label.substring(title.length).trimStart()
            rest.isEmpty() || rest[0] in titleSeparators
        }
    }

    /** Название встречается в подписи отдельным словом. */
    internal fun mentions(label: String, app: HardBlockedApp): Boolean {
        if (app.titleExclusions.any { label.contains(it) }) return false
        return app.storeTitles.any { title ->
            var index = label.indexOf(title)
            while (index >= 0) {
                val before = label.getOrNull(index - 1)
                val after = label.getOrNull(index + title.length)
                if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) {
                    return@any true
                }
                index = label.indexOf(title, index + 1)
            }
            false
        }
    }
}
