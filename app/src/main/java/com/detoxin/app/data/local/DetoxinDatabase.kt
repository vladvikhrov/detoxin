package com.detoxin.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.detoxin.app.data.local.converter.DetoxinTypeConverters
import com.detoxin.app.data.local.dao.BlockedAttemptDao
import com.detoxin.app.data.local.dao.HabitDao
import com.detoxin.app.data.local.dao.ProtectionGapDao
import com.detoxin.app.data.local.dao.UsageDao
import com.detoxin.app.data.local.entity.AppUsageLog
import com.detoxin.app.data.local.entity.BlockedAttempt
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.entity.HabitLog
import com.detoxin.app.data.local.entity.ProtectionGap

@Database(
    entities = [
        AppUsageLog::class,
        BlockedAttempt::class,
        Habit::class,
        HabitLog::class,
        ProtectionGap::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(DetoxinTypeConverters::class)
abstract class DetoxinDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun blockedAttemptDao(): BlockedAttemptDao
    abstract fun habitDao(): HabitDao
    abstract fun protectionGapDao(): ProtectionGapDao
}
