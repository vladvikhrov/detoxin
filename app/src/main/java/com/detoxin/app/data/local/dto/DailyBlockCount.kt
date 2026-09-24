package com.detoxin.app.data.local.dto

import com.detoxin.app.data.local.entity.BlockType

/** Количество блокировок определённого типа за один день. */
data class DailyBlockCount(
    val dateEpochDay: Long,
    val blockType: BlockType,
    val count: Int,
)
