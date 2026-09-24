package com.detoxin.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detoxin.app.data.local.dto.PackageUsageSummary
import com.detoxin.app.data.local.entity.AppUsageLog
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: AppUsageLog): Long

    /** Пакетная вставка выполняется Room в одной транзакции. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(logs: List<AppUsageLog>): List<Long>

    /**
     * Суммарное время по приложениям за закрытый интервал `[startEpochDay, endEpochDay]`,
     * по убыванию времени. Фильтр использует индекс по `dateEpochDay`.
     */
    @Query(
        """
        SELECT packageName, SUM(durationMs) AS totalDurationMs
        FROM app_usage_logs
        WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        GROUP BY packageName
        ORDER BY totalDurationMs DESC
        """
    )
    fun observeUsageSummary(startEpochDay: Long, endEpochDay: Long): Flow<List<PackageUsageSummary>>

    /**
     * Сырые сессии за `[startEpochDay, endEpochDay]` для аналитики: почасовая и
     * по-приложенческая разбивка строится в presentation-слое вне главного потока.
     */
    @Query(
        """
        SELECT * FROM app_usage_logs
        WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        ORDER BY sessionStartTime ASC
        """
    )
    fun observeLogsInRange(startEpochDay: Long, endEpochDay: Long): Flow<List<AppUsageLog>>

    /**
     * Суммарное время за один день по заданным пакетам — основа проверки суточных лимитов.
     * Пакеты без записей в результат не попадают (трактуются как 0).
     */
    @Query(
        """
        SELECT packageName, SUM(durationMs) AS totalDurationMs
        FROM app_usage_logs
        WHERE dateEpochDay = :dateEpochDay AND packageName IN (:packageNames)
        GROUP BY packageName
        """
    )
    suspend fun getDailyTotals(dateEpochDay: Long, packageNames: List<String>): List<PackageUsageSummary>
}
