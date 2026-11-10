package com.detoxin.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.detoxin.app.data.local.entity.ProtectionGap
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProtectionGapDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(gap: ProtectionGap): Long

    /** Незакрытый разрыв: защита выключена прямо сейчас. */
    @Query("SELECT * FROM protection_gaps WHERE endedAtWall IS NULL ORDER BY startedAtWall DESC LIMIT 1")
    abstract suspend fun getOpenGap(): ProtectionGap?

    @Query("UPDATE protection_gaps SET endedAtWall = :endedAtWall WHERE id = :id")
    abstract suspend fun close(id: Long, endedAtWall: Long): Int

    @Query("DELETE FROM protection_gaps WHERE id = :id")
    abstract suspend fun delete(id: Long): Int

    /**
     * Открывает разрыв, если открытого ещё нет. `onUnbind` и `onDestroy` службы вызываются
     * подряд — транзакция гарантирует одну запись на одно выключение.
     */
    @Transaction
    open suspend fun openIfNone(startedAtWall: Long): Boolean {
        if (getOpenGap() != null) return false
        insert(ProtectionGap(startedAtWall = startedAtWall, endedAtWall = null))
        return true
    }

    /** Разрывы, пересекающиеся с `[fromWall, toWall)`; открытый разрыв длится «до сих пор». */
    @Query(
        """
        SELECT * FROM protection_gaps
        WHERE startedAtWall < :toWall AND (endedAtWall IS NULL OR endedAtWall > :fromWall)
        ORDER BY startedAtWall ASC
        """
    )
    abstract suspend fun getOverlapping(fromWall: Long, toWall: Long): List<ProtectionGap>

    @Query(
        """
        SELECT * FROM protection_gaps
        WHERE startedAtWall < :toWall AND (endedAtWall IS NULL OR endedAtWall > :fromWall)
        ORDER BY startedAtWall ASC
        """
    )
    abstract fun observeOverlapping(fromWall: Long, toWall: Long): Flow<List<ProtectionGap>>

    /** Разрывы с причиной [reason], которые ещё идут на момент [nowWall], заканчиваются в [nowWall]. */
    @Query(
        """
        UPDATE protection_gaps SET endedAtWall = :nowWall
        WHERE reason = :reason AND startedAtWall <= :nowWall AND endedAtWall > :nowWall
        """
    )
    abstract suspend fun truncateRunning(reason: String, nowWall: Long): Int
}
