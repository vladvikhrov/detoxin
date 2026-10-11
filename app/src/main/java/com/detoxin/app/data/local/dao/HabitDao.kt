package com.detoxin.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.detoxin.app.data.local.dto.HabitCycleCount
import com.detoxin.app.data.local.dto.HabitLastCompletion
import com.detoxin.app.data.local.dto.HabitWithLogs
import com.detoxin.app.data.local.entity.Habit
import com.detoxin.app.data.local.entity.HabitLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
abstract class HabitDao {

    // ---------- CRUD Habit ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertHabit(habit: Habit): Long

    @Update
    abstract suspend fun updateHabit(habit: Habit): Int

    /** Логи привычки удаляются каскадно (ForeignKey.CASCADE). */
    @Delete
    abstract suspend fun deleteHabit(habit: Habit): Int

    @Query("DELETE FROM habits WHERE id = :habitId")
    abstract suspend fun deleteHabitById(habitId: Long): Int

    @Query("UPDATE habits SET targetDaysPerWeek = :targetDaysPerWeek WHERE id = :habitId")
    abstract suspend fun updateTargetDaysPerWeek(habitId: Long, targetDaysPerWeek: Int): Int

    // ---------- Цикл использования ----------

    /** Новый цикл начинается с [currentEpochDay] включительно (день замены линз — день 1 носки). */
    @Query("UPDATE habits SET cycleResetEpochDay = :currentEpochDay WHERE id = :habitId")
    abstract suspend fun resetHabitCycle(habitId: Long, currentEpochDay: Long): Int

    /** Выполненные дни в текущем цикле по всем привычкам (привычки без отметок — с нулём). */
    @Query(
        """
        SELECT habits.id AS habitId, COUNT(habit_logs.id) AS completedInCycle
        FROM habits
        LEFT JOIN habit_logs
            ON habit_logs.habitId = habits.id
            AND habit_logs.isCompleted = 1
            AND habit_logs.dateEpochDay >= habits.cycleResetEpochDay
        GROUP BY habits.id
        """
    )
    abstract fun observeCycleCounts(): Flow<List<HabitCycleCount>>

    @Query("SELECT * FROM habits WHERE id = :habitId")
    abstract suspend fun getHabitById(habitId: Long): Habit?

    @Query("SELECT * FROM habits ORDER BY createdAt ASC, id ASC")
    abstract fun observeHabits(): Flow<List<Habit>>

    @Query("SELECT * FROM habits ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getAllHabits(): List<Habit>

    /** Эмитит `null`, если привычка удалена: виджет переходит в состояние предупреждения. */
    @Query("SELECT * FROM habits WHERE id = :habitId")
    abstract fun observeHabit(habitId: Long): Flow<Habit?>

    // ---------- Аналитика привычки ----------

    /** Все логи привычки за `[startDay, endDay]` (включая невыполненные) в хронологическом порядке. */
    @Query(
        """
        SELECT * FROM habit_logs
        WHERE habitId = :habitId AND dateEpochDay BETWEEN :startDay AND :endDay
        ORDER BY dateEpochDay ASC
        """
    )
    abstract fun getLogsForHabit(habitId: Long, startDay: Long, endDay: Long): Flow<List<HabitLog>>

    @Query(
        """
        SELECT COUNT(*) FROM habit_logs
        WHERE habitId = :habitId AND isCompleted = 1 AND dateEpochDay BETWEEN :startDay AND :endDay
        """
    )
    abstract suspend fun getCompletionCount(habitId: Long, startDay: Long, endDay: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM habit_logs
        WHERE habitId = :habitId AND isCompleted = 1 AND dateEpochDay BETWEEN :startDay AND :endDay
        """
    )
    abstract fun observeCompletionCount(habitId: Long, startDay: Long, endDay: Long): Flow<Int>

    /** `null` — привычка ещё ни разу не выполнялась. */
    @Query("SELECT MAX(dateEpochDay) FROM habit_logs WHERE habitId = :habitId AND isCompleted = 1")
    abstract suspend fun getLastCompletionDay(habitId: Long): Long?

    /** Последние дни выполнения всех привычек — для детектора заброшенных привычек. */
    @Query(
        """
        SELECT habitId, MAX(dateEpochDay) AS lastCompletedDay
        FROM habit_logs
        WHERE isCompleted = 1
        GROUP BY habitId
        """
    )
    abstract fun observeLastCompletionDays(): Flow<List<HabitLastCompletion>>

    // ---------- Статус за день ----------

    /** `null` — записи за день нет (привычка не отмечалась). */
    @Query("SELECT isCompleted FROM habit_logs WHERE habitId = :habitId AND dateEpochDay = :dateEpochDay")
    abstract suspend fun getCompletion(habitId: Long, dateEpochDay: Long): Boolean?

    @Query("SELECT isCompleted FROM habit_logs WHERE habitId = :habitId AND dateEpochDay = :dateEpochDay")
    abstract fun observeCompletion(habitId: Long, dateEpochDay: Long): Flow<Boolean?>

    // ---------- Toggle ----------

    /** Возвращает -1, если запись за этот день уже существует (уникальный индекс). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLogIfAbsent(log: HabitLog): Long

    @Query(
        """
        UPDATE habit_logs
        SET isCompleted = NOT isCompleted
        WHERE habitId = :habitId AND dateEpochDay = :dateEpochDay
        """
    )
    protected abstract suspend fun invertCompletion(habitId: Long, dateEpochDay: Long): Int

    @Query(
        """
        UPDATE habit_logs
        SET isCompleted = :isCompleted
        WHERE habitId = :habitId AND dateEpochDay = :dateEpochDay
        """
    )
    protected abstract suspend fun updateCompletion(habitId: Long, dateEpochDay: Long, isCompleted: Boolean): Int

    /**
     * Атомарный toggle отметки привычки за день (сегодня или любой прошлый).
     *
     * Если записи нет — создаётся со статусом `true`; иначе `isCompleted` инвертируется.
     * SQL-UPSERT (`ON CONFLICT DO UPDATE`) недоступен: он появился в SQLite 3.24, а на
     * API 29 (minSdk) поставляется SQLite 3.22. Поэтому используется пара
     * `INSERT OR IGNORE` + `UPDATE` внутри одной транзакции — без гонок между
     * AccessibilityService, виджетом и UI.
     *
     * @return новое состояние отметки — по нему UI решает, предлагать ли «Отменить».
     */
    @Transaction
    open suspend fun toggleHabit(habitId: Long, dateEpochDay: Long): Boolean {
        val rowId = insertLogIfAbsent(
            HabitLog(habitId = habitId, dateEpochDay = dateEpochDay, isCompleted = true)
        )
        if (rowId != -1L) return true
        invertCompletion(habitId, dateEpochDay)
        return getCompletion(habitId, dateEpochDay) == true
    }

    /**
     * Идемпотентная установка отметки (для «Отменить»): повторное нажатие не превращается
     * в повторный toggle, даже если пользователь успел коснуться дня ещё раз.
     */
    @Transaction
    open suspend fun setCompletion(habitId: Long, dateEpochDay: Long, isCompleted: Boolean) {
        val rowId = insertLogIfAbsent(
            HabitLog(habitId = habitId, dateEpochDay = dateEpochDay, isCompleted = isCompleted)
        )
        if (rowId == -1L) updateCompletion(habitId, dateEpochDay, isCompleted)
    }

    /**
     * Автоматическая отметка: только если за день ещё нет никакой записи. Ручное решение
     * (в том числе снятая отметка, которая хранится как `isCompleted = 0`) не перезаписывается.
     * @return `true`, если отметка добавлена.
     */
    open suspend fun markCompletedIfAbsent(habitId: Long, dateEpochDay: Long): Boolean =
        insertLogIfAbsent(HabitLog(habitId = habitId, dateEpochDay = dateEpochDay, isCompleted = true)) != -1L

    /** Привычки с автоматическими отметками из источника [autoSource]. */
    @Query("SELECT * FROM habits WHERE autoSource = :autoSource ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getHabitsWithAutoSource(autoSource: String): List<Habit>

    // ---------- Habits with logs ----------

    /**
     * LEFT JOIN с фильтром интервала в условии ON: привычки без логов в интервале
     * сохраняются (для них Room возвращает пустой список). `@Relation` не подходит,
     * так как не умеет фильтровать дочерние строки по параметрам запроса.
     */
    @Transaction
    @Query(
        """
        SELECT habits.*, habit_logs.*
        FROM habits
        LEFT JOIN habit_logs
            ON habit_logs.habitId = habits.id
            AND habit_logs.dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        ORDER BY habits.createdAt ASC, habits.id ASC, habit_logs.dateEpochDay ASC
        """
    )
    protected abstract fun observeHabitLogMap(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<Map<Habit, List<HabitLog>>>

    /** Привычки со всеми логами за `[startEpochDay, endEpochDay]`, в порядке создания. */
    open fun observeHabitsWithLogs(startEpochDay: Long, endEpochDay: Long): Flow<List<HabitWithLogs>> =
        observeHabitLogMap(startEpochDay, endEpochDay).map { grouped ->
            grouped.map { (habit, logs) -> HabitWithLogs(habit = habit, logs = logs) }
        }
}
