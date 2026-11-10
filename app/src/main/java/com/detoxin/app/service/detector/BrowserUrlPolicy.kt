package com.detoxin.app.service.detector

import com.detoxin.app.data.local.entity.BlockType

/**
 * Думскроллинг в браузере: какие адреса закрываются и где у браузеров лежит адресная строка.
 *
 * Чистая логика без Android-зависимостей: служба достаёт текст адресной строки и передаёт его
 * в [classify]. Закрываются `tiktok.com` (с любыми поддоменами) и раздел `youtube.com/shorts`;
 * обычные видео YouTube в браузере не трогаются.
 */
internal object BrowserUrlPolicy {

    /** resource-id (часть после `:id/`) адресной строки известных браузеров. */
    private val urlBarIdsByPackage: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("url_bar"),
        "com.chrome.beta" to listOf("url_bar"),
        "com.chrome.dev" to listOf("url_bar"),
        "com.chrome.canary" to listOf("url_bar"),
        "org.chromium.chrome" to listOf("url_bar"),
        "com.brave.browser" to listOf("url_bar"),
        "com.microsoft.emmx" to listOf("url_bar"),
        "com.vivaldi.browser" to listOf("url_bar"),
        "com.kiwibrowser.browser" to listOf("url_bar"),
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.fenix" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.focus" to listOf("mozac_browser_toolbar_url_view", "display_url"),
        "com.yandex.browser" to listOf("bro_omnibar_address_title_text", "bro_omnibox_collapsed_title"),
        "ru.yandex.searchplugin" to listOf("bro_omnibar_address_title_text", "bro_omnibox_collapsed_title"),
        "com.yandex.browser.lite" to listOf("bro_omnibar_address_title_text", "bro_omnibox_collapsed_title"),
        "com.opera.browser" to listOf("url_field"),
        "com.opera.mini.native" to listOf("url_field"),
        "com.opera.gx" to listOf("url_field"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
        "com.huawei.browser" to listOf("url_bar", "url"),
        "com.hihonor.browser" to listOf("url_bar", "url"),
        "com.mi.globalbrowser" to listOf("url", "search_hint"),
    )

    val packages: Set<String> = urlBarIdsByPackage.keys

    /** Текст для запасного поиска `findAccessibilityNodeInfosByText`, если id адресной строки сменился. */
    val fallbackQueries: List<String> = listOf("tiktok.com", "youtube.com/shorts")

    /** Признаки адресной строки в resource-id для запасного поиска. */
    private val urlIdMarkers = listOf("url", "omni", "address", "location_bar")

    fun urlBarIds(packageName: String): List<String> = urlBarIdsByPackage[packageName].orEmpty()

    fun looksLikeUrlBarId(viewIdEntry: String?): Boolean {
        val id = viewIdEntry?.lowercase() ?: return false
        return urlIdMarkers.any { id.contains(it) }
    }

    /**
     * @param raw текст адресной строки: со схемой или без, возможно с невидимыми символами
     * направления письма, которые добавляет Chrome.
     * @return тип блокировки или `null`, если адрес разрешён либо это не адрес (поисковый запрос).
     */
    fun classify(raw: String?): BlockType? {
        val text = raw?.filterNot(::isInvisible)?.trim()?.lowercase() ?: return null
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null

        val withoutScheme = text.substringAfter("://", text)
        val authority = withoutScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val path = withoutScheme.substring(authority.length)
        val host = authority.substringAfterLast('@').substringBefore(':').trimEnd('.')
        if (!host.contains('.')) return null

        return when {
            isDomain(host, "tiktok.com") -> BlockType.BROWSER_TIKTOK
            isDomain(host, "youtube.com") && isShortsPath(path) -> BlockType.BROWSER_SHORTS
            else -> null
        }
    }

    private fun isDomain(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    /** `/shorts`, `/shorts/…`, `/shorts?…`; `/shortsomething` не считается. */
    private fun isShortsPath(path: String): Boolean {
        if (!path.startsWith("/shorts")) return false
        val next = path.getOrNull("/shorts".length) ?: return true
        return next == '/' || next == '?' || next == '#'
    }

    private fun isInvisible(char: Char): Boolean =
        char == '​' || char == '‎' || char == '‏' || char == '﻿' ||
            char in '‪'..'‮' || char in '⁦'..'⁩'
}
