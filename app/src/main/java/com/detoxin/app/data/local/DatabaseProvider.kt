package com.detoxin.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Потокобезопасный синглтон [DetoxinDatabase] без DI-фреймворков.
 *
 * AccessibilityService, Glance-виджет и Activity живут в одном процессе, но
 * создаются системой независимо и могут одновременно запросить базу.
 * Double-checked locking гарантирует ровно один экземпляр Room (и один пул
 * соединений SQLite), а `@Volatile` — видимость полностью сконструированного
 * объекта всем потокам без захвата монитора на «горячем» пути.
 */
object DatabaseProvider {

    private const val DATABASE_NAME = "detoxin.db"

    @Volatile
    private var instance: DetoxinDatabase? = null

    fun getDatabase(context: Context): DetoxinDatabase =
        instance ?: synchronized(this) {
            instance ?: buildDatabase(context.applicationContext).also { instance = it }
        }

    /** Используется applicationContext, чтобы не удерживать Activity/Service от сборки мусора. */
    private fun buildDatabase(appContext: Context): DetoxinDatabase =
        Room.databaseBuilder(appContext, DetoxinDatabase::class.java, DATABASE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()

    fun databaseFile(context: Context): File = context.applicationContext.getDatabasePath(DATABASE_NAME)

    /**
     * Замена файла базы содержимым [source] (импорт резервной копии).
     *
     * Всё происходит под тем же монитором, что и [getDatabase]: пока идёт замена, остальные
     * потоки ждут и не могут заново открыть старый файл. Порядок: закрыть Room (последнее
     * соединение сбрасывает WAL в основной файл) → сохранить прежнюю базу рядом как
     * `detoxin.db.before-import` → удалить `-wal`/`-shm` → скопировать новый файл → открыть и
     * прогнать миграции. Если новая база не открылась, прежняя возвращается на место.
     *
     * После успешной замены процесс нужно перезапустить: ранее выданные DAO привязаны к
     * закрытому экземпляру.
     *
     * @throws Exception причина, по которой новая база не открылась (прежняя уже восстановлена).
     */
    fun replaceWith(context: Context, source: File) {
        val appContext = context.applicationContext
        synchronized(this) {
            instance?.close()
            instance = null
            val target = databaseFile(appContext)
            val rollback = File(target.parentFile, "$DATABASE_NAME.before-import")
            val hadDatabase = target.exists()
            if (hadDatabase) target.copyTo(rollback, overwrite = true)
            try {
                deleteSidecars(target)
                source.copyTo(target, overwrite = true)
                val reopened = buildDatabase(appContext)
                try {
                    // Открытие запускает миграции и проверку схемы Room.
                    reopened.openHelper.writableDatabase
                } catch (e: Exception) {
                    reopened.close()
                    throw e
                }
                instance = reopened
            } catch (e: Exception) {
                deleteSidecars(target)
                if (hadDatabase) rollback.copyTo(target, overwrite = true) else target.delete()
                throw e
            }
        }
    }

    private fun deleteSidecars(database: File) {
        File(database.path + "-wal").delete()
        File(database.path + "-shm").delete()
        File(database.path + "-journal").delete()
    }
}
