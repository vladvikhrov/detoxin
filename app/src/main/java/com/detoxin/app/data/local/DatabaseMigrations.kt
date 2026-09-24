package com.detoxin.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Явные миграции: пользовательские данные никогда не удаляются деструктивно.
 *
 * 1 → 2: цикл использования привычки. `ADD COLUMN` с `NOT NULL DEFAULT` мгновенно
 * заполняет существующие строки: цикл 30 дней, отсчёт с момента создания привычки.
 */
internal object DatabaseMigrations {

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN cycleLength INTEGER NOT NULL DEFAULT 30")
            db.execSQL("ALTER TABLE habits ADD COLUMN cycleResetEpochDay INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
