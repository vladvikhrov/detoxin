package com.detoxin.app.data.local.dto

/** Суммарное время использования одного приложения за интервал дат. */
data class PackageUsageSummary(
    val packageName: String,
    val totalDurationMs: Long,
)
