package com.detoxin.app.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.detoxin.app.bedtime.BedtimeRepository
import com.detoxin.app.data.local.DatabaseProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/**
 * Мост для дневника Retox: только чтение, только сводка по дням (без названий приложений
 * и отдельных сессий). Доступ — по разрешению с уровнем `signature`: читать может лишь
 * приложение, подписанное тем же ключом, что и Detoxin.
 *
 * Запрос: `content://com.detoxin.app.summary/days?from=2026-10-01&to=2026-11-12`
 * (не больше [MAX_DAYS] дней). Колонки — [Contract].
 */
class SummaryProvider : ContentProvider() {

    object Contract {
        const val AUTHORITY = "com.detoxin.app.summary"
        const val PATH_DAYS = "days"
        const val PARAM_FROM = "from"
        const val PARAM_TO = "to"

        const val DATE = "date"
        const val SCREEN_MINUTES = "screen_minutes"
        const val NIGHT_MINUTES = "night_minutes"
        const val BEDTIME_KEPT = "bedtime_kept"
        const val ACTUAL_BEDTIME = "actual_bedtime"
        const val PROTECTION_GAPS = "protection_gaps"
        const val BLOCKED_ATTEMPTS = "blocked_attempts"

        val COLUMNS = arrayOf(
            DATE, SCREEN_MINUTES, NIGHT_MINUTES, BEDTIME_KEPT, ACTUAL_BEDTIME, PROTECTION_GAPS, BLOCKED_ATTEMPTS,
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri.pathSegments == listOf(Contract.PATH_DAYS)) { "Неизвестный путь: $uri" }
        val context = requireNotNull(context)
        val today = LocalDate.now()
        val to = uri.getQueryParameter(Contract.PARAM_TO)?.let(LocalDate::parse) ?: today
        val from = uri.getQueryParameter(Contract.PARAM_FROM)?.let(LocalDate::parse) ?: to.minusDays(29)
        require(!from.isAfter(to)) { "from позже to" }
        require(from.plusDays(MAX_DAYS.toLong()).isAfter(to)) { "Не больше $MAX_DAYS дней за запрос" }

        val zone = ZoneId.systemDefault()
        val nowWall = System.currentTimeMillis()
        // Ночь, начавшаяся вечером последнего дня, заканчивается на следующий день.
        val logsFrom = from.toEpochDay()
        val logsTo = to.plusDays(1).toEpochDay()
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

        val summaries = runBlocking {
            val db = DatabaseProvider.getDatabase(context)
            DaySummaryCalculator.build(
                from = from,
                to = to,
                logs = db.usageDao().observeLogsInRange(logsFrom, logsTo).first(),
                attempts = db.blockedAttemptDao().observeAttemptsInRange(from.toEpochDay(), to.toEpochDay()).first(),
                gaps = db.protectionGapDao().getOverlapping(rangeStart, rangeEnd),
                bedtime = BedtimeRepository.current(context).effectiveAt(nowWall),
                nowWall = nowWall,
                zone = zone,
            )
        }

        return MatrixCursor(Contract.COLUMNS, summaries.size).apply {
            summaries.forEach { s ->
                addRow(
                    arrayOf<Any?>(
                        s.date.toString(),
                        s.screenMinutes,
                        s.nightMinutes,
                        s.bedtimeKept?.let { if (it) 1 else 0 },
                        s.actualBedtime,
                        s.protectionGaps,
                        s.blockedAttempts,
                    ),
                )
            }
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.${Contract.AUTHORITY}.${Contract.PATH_DAYS}"

    // Мост только для чтения.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Только чтение")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Только чтение")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Только чтение")

    private companion object {
        const val MAX_DAYS = 400
    }
}
