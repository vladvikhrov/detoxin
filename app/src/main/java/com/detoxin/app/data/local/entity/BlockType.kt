package com.detoxin.app.data.local.entity

/**
 * Тип сработавшей блокировки. Хранится в БД по стабильному строковому имени
 * (см. [com.detoxin.app.data.local.converter.DetoxinTypeConverters]), поэтому
 * переупорядочивание констант не ломает уже сохранённые данные.
 */
enum class BlockType {
    SHORTS,
    VK_FEED,
    VK_CLIPS,
    TIKTOK_TIMEOUT,
}
