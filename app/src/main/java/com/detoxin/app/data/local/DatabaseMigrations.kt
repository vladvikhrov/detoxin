package com.detoxin.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Явные миграции: пользовательские данные никогда не удаляются деструктивно.
 *
 * 1 → 2: цикл использования привычки. `ADD COLUMN` с `NOT NULL DEFAULT` мгновенно
 * заполняет существующие строки: цикл 30 дней, отсчёт с момента создания привычки.
 *
 * 2 → 3: режим «Отбой». Колонка `habits.autoSource` (источник автоотметок, по умолчанию `NULL`)
 * и таблица `protection_gaps` — периоды, когда служба защиты была выключена.
 * DDL совпадает с тем, что генерирует Room для [com.detoxin.app.data.local.entity.ProtectionGap].
 */
internal object DatabaseMigrations {

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN cycleLength INTEGER NOT NULL DEFAULT 30")
            db.execSQL("ALTER TABLE habits ADD COLUMN cycleResetEpochDay INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN autoSource TEXT DEFAULT NULL")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `protection_gaps` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startedAtWall` INTEGER NOT NULL, " +
                    "`endedAtWall` INTEGER)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_protection_gaps_startedAtWall` " +
                    "ON `protection_gaps` (`startedAtWall`)"
            )
        }
    }

    /** 3 → 4: причина разрыва защиты (выключенная служба или экстренная разблокировка). */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE protection_gaps ADD COLUMN reason TEXT NOT NULL DEFAULT 'SERVICE_OFF'")
        }
    }

    /** 4 → 5: привычки «два раза в день» (утро и вечер). */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN timesPerDay INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE habit_logs ADD COLUMN slotsDone INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
