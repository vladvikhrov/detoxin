package com.detoxin.app.service

import android.os.SystemClock
import android.util.Log
import com.detoxin.app.data.local.dao.BlockedAttemptDao
import com.detoxin.app.data.local.entity.BlockedAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * Буфер событий блокировки: неблокирующая запись из любого потока и пакетный сброс в Room.
 *
 * Производители вызывают [submit] (`trySend`, никогда не приостанавливается и не блокирует
 * главный поток). Единственный потребитель на [Dispatchers.IO] копит записи и сбрасывает их
 * одной транзакцией `insertAll`, когда:
 *  - набралось [BATCH_SIZE] событий, или
 *  - прошло [FLUSH_INTERVAL_MS] с момента появления первого несброшенного события.
 * Пока буфер пуст, потребитель спит на `receive` без периодических пробуждений.
 *
 * У движка собственный scope: при [close] канал закрывается, потребитель дочитывает остаток,
 * делает финальный сброс и завершается сам — отмена scope сервиса не теряет события.
 */
class LogEventEngine(private val blockedAttemptDao: BlockedAttemptDao) {

    private val channel = Channel<BlockedAttempt>(capacity = Channel.BUFFERED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { drainLoop() }
    }

    /** @return `false`, если буфер переполнен или движок уже закрыт. */
    fun submit(attempt: BlockedAttempt): Boolean {
        val result = channel.trySend(attempt)
        if (result.isFailure) {
            Log.w(TAG, "BlockedAttempt dropped: buffer full or engine closed")
        }
        return result.isSuccess
    }

    fun close() {
        channel.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun drainLoop() {
        val buffer = ArrayList<BlockedAttempt>(BATCH_SIZE)
        var flushDeadline = 0L

        while (true) {
            val result: ChannelResult<BlockedAttempt>? = if (buffer.isEmpty()) {
                channel.receiveCatching()
            } else {
                val remaining = (flushDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                select {
                    channel.onReceiveCatching { it }
                    onTimeout(remaining) { null }
                }
            }

            when {
                result == null -> flush(buffer)
                result.isClosed -> {
                    withContext(NonCancellable) { flush(buffer) }
                    return
                }
                else -> {
                    if (buffer.isEmpty()) {
                        flushDeadline = SystemClock.elapsedRealtime() + FLUSH_INTERVAL_MS
                    }
                    buffer += result.getOrThrow()
                    if (buffer.size >= BATCH_SIZE) flush(buffer)
                }
            }
        }
    }

    private suspend fun flush(buffer: MutableList<BlockedAttempt>) {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        buffer.clear()
        try {
            blockedAttemptDao.insertAll(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist ${batch.size} blocked attempts", e)
        }
    }

    private companion object {
        const val TAG = "LogEventEngine"
        const val BATCH_SIZE = 10
        const val FLUSH_INTERVAL_MS = 2_000L
    }
}
