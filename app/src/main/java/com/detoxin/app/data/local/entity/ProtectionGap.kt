package com.detoxin.app.data.local.entity

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
)
