package com.detoxin.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Факт перехвата попытки открыть заблокированный контент.
 * [timestamp] — момент срабатывания (`Instant.toEpochMilli()`),
 * [dateEpochDay] — локальный день срабатывания для быстрой группировки.
 */
@Entity(
    tableName = "blocked_attempts",
    indices = [
        Index(value = ["dateEpochDay"]),
        Index(value = ["blockType"]),
    ],
)
data class BlockedAttempt(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val blockType: BlockType,
    val timestamp: Long,
    val dateEpochDay: Long,
)
