package com.detoxin.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

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
}
