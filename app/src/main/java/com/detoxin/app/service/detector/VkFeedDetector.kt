package com.detoxin.app.service.detector

import com.detoxin.app.data.local.entity.BlockType

/**
 * Эвристика разделов «Клипы» и «Лента» ВКонтакте.
 *
 * Сами подписи вкладок видны на любом экране, поэтому решение принимается только по
 * АКТИВНОЙ вкладке (`isSelected` / `isFocused` у ноды или её контейнера) либо по
 * заголовку экрана в тулбаре. Если активна вкладка мессенджера или профиля, детектор
 * немедленно возвращает `null`: чаты и профиль никогда не блокируются.
 */
internal object VkFeedDetector {

    const val PACKAGE_NAME = "com.vkontakte.android"

    private val clipsKeywords = setOf("клипы", "clips")
    private val feedKeywords = setOf("лента", "новости", "главная", "feed", "news", "home")
    private val allowedKeywords = setOf(
        "мессенджер", "чаты", "сообщения", "профиль",
        "messenger", "chats", "messages", "profile",
    )

    /** Строки для системного поиска `findAccessibilityNodeInfosByText`. */
    val anchorQueries: List<String> = listOf("Клипы", "Clips", "Лента", "Главная", "Feed")

    private val titleIdMarkers = listOf("toolbar", "title")

    fun detect(nodes: List<NodeSnapshot>): BlockType? {
        val activeLabels = nodes.filter { it.isActive }.flatMap { it.normalizedLabels }
        val titleLabels = nodes.filter { it.isScreenTitle() }.mapNotNull { it.text.normalizeLabel() }

        if (activeLabels.any { it.matchesKeyword(allowedKeywords) }) return null
        if (titleLabels.any { it.matchesKeyword(allowedKeywords) }) return null

        return when {
            activeLabels.any { it.matchesKeyword(clipsKeywords) } -> BlockType.VK_CLIPS
            titleLabels.any { it.matchesKeyword(clipsKeywords) } -> BlockType.VK_CLIPS
            activeLabels.any { it.matchesKeyword(feedKeywords) } -> BlockType.VK_FEED
            titleLabels.any { it.matchesKeyword(feedKeywords) } -> BlockType.VK_FEED
            else -> null
        }
    }

    private fun NodeSnapshot.isScreenTitle(): Boolean {
        val id = viewIdEntry ?: return false
        return !id.contains("tab") && titleIdMarkers.any { id.contains(it) }
    }
}
