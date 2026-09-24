package com.detoxin.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Одна сессия использования приложения.
 *
 * [dateEpochDay] — денормализованный `LocalDate.toEpochDay()` дня начала сессии:
 * агрегирующие запросы фильтруют по индексированному целому числу, а не вычисляют
 * дату из [sessionStartTime] для каждой строки.
 */
@Entity(
    tableName = "app_usage_logs",
    indices = [
        Index(value = ["dateEpochDay"]),
        Index(value = ["packageName"]),
    ],
)
data class AppUsageLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val sessionStartTime: Long,
    val durationMs: Long,
    val dateEpochDay: Long,
)
