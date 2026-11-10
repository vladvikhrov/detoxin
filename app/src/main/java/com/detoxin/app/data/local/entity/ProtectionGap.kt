package com.detoxin.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Период, когда служба защиты Detoxin не работала (выключена в настройках специальных
 * возможностей или процесс был убит). [endedAtWall] `null` — защита всё ещё выключена.
 *
 * Хранятся только две метки времени: что делал пользователь в этот период, не записывается —
 * итог по TikTok/YouTube считается из системной статистики в момент показа.
 */
@Entity(
    tableName = "protection_gaps",
    indices = [Index(value = ["startedAtWall"])],
)
data class ProtectionGap(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAtWall: Long,
    val endedAtWall: Long?,
    /** Причина разрыва ([ProtectionGapReason.key]); `defaultValue` совпадает с DDL миграции 3→4. */
    @ColumnInfo(defaultValue = "SERVICE_OFF")
    val reason: String = ProtectionGapReason.SERVICE_OFF.key,
)

/** Почему защита не работала. В базе хранится стабильная строка [key]. */
enum class ProtectionGapReason(val key: String, val title: String) {
    /** Служба специальных возможностей выключена или процесс убит. */
    SERVICE_OFF("SERVICE_OFF", "Защита выключена"),

    /** Экстренная разблокировка, включённая пользователем после PIN и контрольной фразы. */
    EMERGENCY("EMERGENCY", "Экстренная разблокировка");

    companion object {
        fun fromKey(key: String?): ProtectionGapReason = entries.firstOrNull { it.key == key } ?: SERVICE_OFF
    }
}
