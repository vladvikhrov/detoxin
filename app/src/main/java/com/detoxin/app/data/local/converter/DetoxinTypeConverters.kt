package com.detoxin.app.data.local.converter

import androidx.room.TypeConverter
import com.detoxin.app.data.local.entity.BlockType

/**
 * Конвертеры Room для [BlockType].
 *
 * В колонке хранится `enum.name` (TEXT), а не `ordinal`: имя устойчиво к
 * добавлению и переупорядочиванию констант. Неизвестное значение приводит к явному
 * исключению вместо молчаливой подмены типа, что исключает порчу статистики.
 */
class DetoxinTypeConverters {

    @TypeConverter
    fun fromBlockType(blockType: BlockType): String = blockType.name

    @TypeConverter
    fun toBlockType(value: String): BlockType =
        BlockType.entries.firstOrNull { it.name == value }
            ?: throw IllegalStateException("Unknown BlockType value in database: '$value'")
}
