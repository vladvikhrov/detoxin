package com.detoxin.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detoxin.app.data.local.dto.DailyBlockCount
import com.detoxin.app.data.local.entity.BlockedAttempt
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAttemptDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attempt: BlockedAttempt): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(attempts: List<BlockedAttempt>): List<Long>

    /**
     * Количество блокировок по дням и типам за `[startEpochDay, endEpochDay]`.
     * Дни без блокировок в результат не попадают — UI дополняет их нулями.
     */
    @Query(
        """
        SELECT dateEpochDay, blockType, COUNT(*) AS count
        FROM blocked_attempts
        WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        GROUP BY dateEpochDay, blockType
        ORDER BY dateEpochDay ASC, blockType ASC
        """
    )
    fun observeDailyBlockCounts(startEpochDay: Long, endEpochDay: Long): Flow<List<DailyBlockCount>>

    /** Сырые события за `[startEpochDay, endEpochDay]` для почасовой разбивки на экране аналитики. */
    @Query(
        """
        SELECT * FROM blocked_attempts
        WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        ORDER BY timestamp ASC
        """
    )
    fun observeAttemptsInRange(startEpochDay: Long, endEpochDay: Long): Flow<List<BlockedAttempt>>
}
