package com.detoxin.app.service.detector

/**
 * Эвристика открытого раздела YouTube Shorts без привязки к обфусцированным id.
 *
 * Срабатывает при любом из признаков:
 * 1. Навигация: активная (selected) вкладка нижней панели подписана «Shorts» / «Шортс» /
 *    «Короткие видео». Полка Shorts в главной ленте не выбрана и потому игнорируется.
 * 2. Контейнер: вертикальный пейджер/список (className) с contentDescription «Shorts».
 * 3. Плеер: стабильные (необфусцированные) resource-id плеера Shorts семейства `reel_*`.
 */
internal object YouTubeShortsDetector {

    const val PACKAGE_NAME = "com.google.android.youtube"

    /** Строки для системного поиска `findAccessibilityNodeInfosByText`. */
    val anchorQueries: List<String> = listOf("Shorts", "Шортс", "Короткие видео")

    private val shortsKeywords = setOf("shorts", "шортс", "короткие видео")

    private val pagerClassSuffixes = listOf("ViewPager", "ViewPager2", "RecyclerView")

    private val playerIdMarkers = listOf(
        "reel_player",
        "reel_recycler",
        "reel_watch",
        "reel_time_bar",
        "shorts_player",
    )

    fun isShortsOpen(nodes: List<NodeSnapshot>): Boolean = nodes.any { node ->
        isSelectedShortsTab(node) || isShortsContainer(node) || isShortsPlayer(node)
    }

    private fun isSelectedShortsTab(node: NodeSnapshot): Boolean =
        (node.isSelected || node.parentSelected) &&
            node.normalizedLabels.any { it.matchesKeyword(shortsKeywords) }

    private fun isShortsContainer(node: NodeSnapshot): Boolean {
        val className = node.className ?: return false
        val description = node.contentDescription.normalizeLabel() ?: return false
        return pagerClassSuffixes.any { className.endsWith(it) } && description.matchesKeyword(shortsKeywords)
    }

    private fun isShortsPlayer(node: NodeSnapshot): Boolean {
        val id = node.viewIdEntry ?: return false
        return playerIdMarkers.any { id.startsWith(it) }
    }
}
