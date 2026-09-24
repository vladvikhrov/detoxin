package com.detoxin.app.service

import android.util.Log
import com.detoxin.app.data.local.dao.UsageDao
import com.detoxin.app.data.local.entity.AppUsageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Строго упорядоченная очередь операций с `app_usage_logs` на [Dispatchers.IO].
 *
 * Один потребитель выполняет команды в порядке отправки, поэтому чтение суточных сумм
 * гарантированно видит все записи, поставленные в очередь до него, и ни одной после.
 * На этом свойстве [AppUsageTracker] без гонок совмещает кэш сумм из БД с ещё не
 * записанными отрезками.
 *
 * Собственный scope: после [close] потребитель дописывает хвост очереди и завершается сам,
 * поэтому финальный отрезок при остановке сервиса не теряется.
 */
internal class UsageWriteQueue(private val usageDao: UsageDao) {

    private sealed interface Command {
        data class Write(val log: AppUsageLog) : Command
        data class ReadDailyTotals(
            val dateEpochDay: Long,
            val packageNames: List<String>,
            val onResult: (Map<String, Long>) -> Unit,
        ) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (command in commands) execute(command)
        }
    }

    fun write(log: AppUsageLog) {
        commands.trySend(Command.Write(log))
    }

    /** [onResult] вызывается в главном потоке. */
    fun readDailyTotals(dateEpochDay: Long, packageNames: List<String>, onResult: (Map<String, Long>) -> Unit) {
        commands.trySend(Command.ReadDailyTotals(dateEpochDay, packageNames, onResult))
    }

    fun close() {
        commands.close()
    }

    private suspend fun execute(command: Command) {
        try {
            when (command) {
                is Command.Write -> usageDao.insert(command.log)
                is Command.ReadDailyTotals -> {
                    val totals = usageDao.getDailyTotals(command.dateEpochDay, command.packageNames)
                        .associate { it.packageName to it.totalDurationMs }
                    withContext(Dispatchers.Main.immediate) { command.onResult(totals) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Usage storage command failed: ${command::class.simpleName}", e)
        }
    }

    private companion object {
        const val TAG = "UsageWriteQueue"
    }
}
