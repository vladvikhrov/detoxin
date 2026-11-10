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

    /** Запуск полностью заблокированного приложения (TikTok, YouTube, Яндекс Музыка). */
    APP_BLOCKED,

    /** Экран установки заблокированного приложения в магазине или установщике пакетов. */
    INSTALL_BLOCKED,

    /** tiktok.com, открытый в браузере. */
    BROWSER_TIKTOK,

    /** youtube.com/shorts, открытый в браузере. */
    BROWSER_SHORTS,
}
