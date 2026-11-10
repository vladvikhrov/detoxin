package com.detoxin.app.data.local.backup

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import com.detoxin.app.data.local.DatabaseProvider
import com.detoxin.app.data.local.DetoxinDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Итог экспорта или импорта: [message] показывается пользователю как есть. */
sealed interface BackupResult {
    data class Success(val message: String) : BackupResult
    data class Failure(val message: String) : BackupResult
}

/**
 * Офлайн-резервная копия базы через Storage Access Framework: файл выбирает пользователь в
 * системном диалоге, поэтому приложению не нужны ни разрешения на хранилище, ни сеть.
 *
 * Экспорт не закрывает Room (служба продолжает писать): WAL сбрасывается в основной файл
 * (`wal_checkpoint(TRUNCATE)`), затем файл копируется внутри пишущей транзакции — пока она
 * открыта, никто другой не может зафиксировать изменения и запустить новый checkpoint, так что
 * основной файл неподвижен. Копия проверяется `PRAGMA integrity_check` и только после этого
 * отдаётся в выбранный документ.
 *
 * Импорт: документ копируется во временный файл, проверяется (заголовок SQLite,
 * `integrity_check`, версия схемы, обязательные таблицы), после чего
 * [DatabaseProvider.replaceWith] закрывает соединения и подменяет файл. Старые версии схемы
 * доводятся до текущей обычными миграциями Room.
 */
object DatabaseBackup {

    private const val TAG = "DatabaseBackup"
    private const val CHECKPOINT_ATTEMPTS = 5
    private const val CHECKPOINT_RETRY_MS = 150L
    private const val MAX_IMPORT_BYTES = 512L * 1024L * 1024L
    private const val SQLITE_MAGIC = "SQLite format 3\u0000"

    private val requiredTables = setOf("habits", "habit_logs", "app_usage_logs", "blocked_attempts", "room_master_table")

    private val mutex = Mutex()

    /** Ничего не делает: повреждённый временный файл не нужно удалять обработчиком по умолчанию. */
    private val quietErrorHandler = DatabaseErrorHandler { }

    suspend fun export(context: Context, destination: Uri): BackupResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            val snapshot = File(appContext.cacheDir, "detoxin-export.db")
            try {
                if (!copyConsistentSnapshot(appContext, snapshot)) {
                    return@withLock BackupResult.Failure("База сейчас занята. Повторите экспорт через несколько секунд.")
                }
                validate(snapshot)?.let { problem ->
                    return@withLock BackupResult.Failure("Копия не прошла проверку: $problem")
                }
                val output = appContext.contentResolver.openOutputStream(destination, "wt")
                    ?: return@withLock BackupResult.Failure("Не удалось открыть выбранный файл для записи")
                output.use { stream -> snapshot.inputStream().use { it.copyTo(stream) } }
                BackupResult.Success("Резервная копия сохранена (${snapshot.length() / 1024} КБ)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                BackupResult.Failure("Экспорт не удался: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                deleteWithSidecars(snapshot)
            }
        }
    }

    /**
     * @return [BackupResult.Success] — база заменена, вызывающий код обязан перезапустить процесс.
     */
    suspend fun import(context: Context, source: Uri): BackupResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            val candidate = File(appContext.cacheDir, "detoxin-import.db")
            try {
                val input = appContext.contentResolver.openInputStream(source)
                    ?: return@withLock BackupResult.Failure("Не удалось открыть выбранный файл")
                val copied = input.use { stream ->
                    candidate.outputStream().use { out -> copyLimited(stream, out, MAX_IMPORT_BYTES) }
                }
                if (!copied) return@withLock BackupResult.Failure("Файл слишком большой для резервной копии Detoxin")
                validate(candidate)?.let { problem ->
                    return@withLock BackupResult.Failure("Файл не подходит: $problem")
                }
                DatabaseProvider.replaceWith(appContext, candidate)
                BackupResult.Success("База восстановлена из резервной копии")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                BackupResult.Failure("Импорт не удался, прежние данные сохранены: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                deleteWithSidecars(candidate)
            }
        }
    }

    /**
     * Проверка файла SQLite. Открывается отдельным соединением вне Room.
     * @return `null`, если файл годен, иначе описание проблемы.
     */
    fun validate(file: File): String? {
        if (!file.isFile || file.length() < SQLITE_MAGIC.length) return "это не база SQLite"
        val header = ByteArray(SQLITE_MAGIC.length)
        file.inputStream().use { if (it.read(header) != header.size) return "это не база SQLite" }
        if (!header.contentEquals(SQLITE_MAGIC.toByteArray(Charsets.US_ASCII))) return "это не база SQLite"

        val database = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, quietErrorHandler)
        } catch (e: SQLiteException) {
            return "файл не открывается как база SQLite"
        }
        try {
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (!cursor.moveToFirst() || !cursor.getString(0).equals("ok", ignoreCase = true)) {
                    return "нарушена целостность (integrity_check)"
                }
            }
            val version = database.version
            if (version < 1 || version > DetoxinDatabase.VERSION) {
                return "версия схемы $version не поддерживается этой версией Detoxin"
            }
            val tables = HashSet<String>()
            database.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            val missing = requiredTables - tables
            if (missing.isNotEmpty()) return "это не база Detoxin (нет таблиц: ${missing.sorted().joinToString()})"
            return null
        } catch (e: SQLiteException) {
            return "ошибка чтения: ${e.message ?: "неизвестная"}"
        } finally {
            database.close()
        }
    }

    /** @return `false`, если WAL не удалось полностью сбросить (активные читатели). */
    private suspend fun copyConsistentSnapshot(context: Context, snapshot: File): Boolean {
        val room = DatabaseProvider.getDatabase(context)
        val source = DatabaseProvider.databaseFile(context)
        repeat(CHECKPOINT_ATTEMPTS) {
            var copied = false
            if (checkpoint(room)) {
                room.runInTransaction {
                    // Между checkpoint и началом транзакции мог успеть коммит: проверяем, что WAL пуст.
                    if (File(source.path + "-wal").length() == 0L) {
                        deleteWithSidecars(snapshot)
                        source.copyTo(snapshot, overwrite = true)
                        copied = true
                    }
                }
            }
            if (copied) return true
            delay(CHECKPOINT_RETRY_MS)
        }
        return false
    }

    /** `wal_checkpoint(TRUNCATE)` возвращает строку `(busy, log, checkpointed)`; busy = 0 — WAL сброшен целиком. */
    private fun checkpoint(room: DetoxinDatabase): Boolean =
        room.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 0
        }

    @Throws(IOException::class)
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > limit) return false
            output.write(buffer, 0, read)
        }
    }

    private fun deleteWithSidecars(file: File) {
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }
}
