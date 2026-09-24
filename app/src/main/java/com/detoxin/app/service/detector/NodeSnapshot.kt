package com.detoxin.app.service.detector

/**
 * Неизменяемый снимок значимых свойств одной ноды.
 *
 * Эвристики работают только со снимками: все [android.view.accessibility.AccessibilityNodeInfo]
 * утилизируются прямо во время обхода, и никакая логика детекции не держит живые ноды.
 *
 * @property viewIdEntry часть resource-id после `:id/` (например, `reel_player_page_container`).
 * @property parentSelected выбран ли непосредственный родитель: у нижних вкладок флаг
 * `isSelected` часто стоит на контейнере вкладки, а подпись лежит в дочернем TextView.
 * @property depth глубина от корня окна; [ANCHOR_DEPTH] — нода найдена системным поиском по тексту.
 */
internal data class NodeSnapshot(
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewIdEntry: String?,
    val isSelected: Boolean,
    val isFocused: Boolean,
    val parentSelected: Boolean,
    val depth: Int,
) {
    /** Нода или её контейнер отмечены как активная вкладка / элемент в фокусе. */
    val isActive: Boolean
        get() = isSelected || isFocused || parentSelected

    /** Нормализованные подписи ноды (text и contentDescription) для сравнения с ключевыми словами. */
    val normalizedLabels: List<String>
        get() = listOfNotNull(text.normalizeLabel(), contentDescription.normalizeLabel())

    companion object {
        const val ANCHOR_DEPTH = -1
    }
}

internal fun String?.normalizeLabel(): String? =
    this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/**
 * Подпись совпадает с ключевым словом целиком или является его расширением через запятую
 * («Shorts, вкладка 2 из 5»). Подстрочное совпадение не используется, чтобы заголовки
 * вроде «Shorts-ролики канала» не давали ложных срабатываний.
 */
internal fun String.matchesKeyword(keywords: Set<String>): Boolean =
    keywords.any { keyword -> this == keyword || startsWith("$keyword,") }
