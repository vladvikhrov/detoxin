package com.detoxin.app.usage

import android.content.Context
import android.content.SharedPreferences
import com.detoxin.app.core.TrackedApp

/**
 * Запомненная блокировка лимита.
 *
 * @property untilElapsed конец блокировки по `elapsedRealtime` загрузки [bootCount].
 * @property untilWall тот же момент по настенным часам — запасной ориентир после перезагрузки.
 */
data class LatchEntry(
    val kind: LimitBlock.Kind,
    val untilElapsed: Long,
    val untilWall: Long,
    val bootCount: Int,
)

/**
 * Античит лимитов — чистая логика «защёлки».
 *
 * Лимиты считаются по настенным часам: сутки, час и окно сна — календарные понятия. Поэтому
 * перевод системного времени (назад во вчера или вперёд в завтра) без защёлки вернул бы свежий
 * лимит. Как только лимит сработал, его оставшаяся длительность фиксируется по монотонным
 * часам (`elapsedRealtime` + номер загрузки), которые пользователь изменить не может. Пока
 * защёлка не истекла, приложение остаётся закрытым, что бы ни показывали настенные часы.
 * После перезагрузки монотонные часы обнуляются — тогда срок сверяется с настенным временем,
 * так что перевод часов назад по-прежнему ничего не даёт.
 */
object LimitLatchPolicy {

    /** Разница меньше этой не считается продлением: защёлка не переписывается каждую секунду. */
    const val REWRITE_TOLERANCE_MS = 5_000L

    fun remainingMs(entry: LatchEntry, nowWall: Long, nowElapsed: Long, bootCount: Int): Long {
        val remaining = if (entry.bootCount == bootCount) entry.untilElapsed - nowElapsed else entry.untilWall - nowWall
        return remaining.coerceAtLeast(0L)
    }

    /**
     * @param block решение по настенным часам (может быть `null`, если часы переведены).
     * @return действующая блокировка и запись, которую нужно сохранить (`null` — стереть).
     */
    fun resolve(
        existing: LatchEntry?,
        block: LimitBlock?,
        nowWall: Long,
        nowElapsed: Long,
        bootCount: Int,
    ): Resolution {
        val latchedMs = existing?.let { remainingMs(it, nowWall, nowElapsed, bootCount) } ?: 0L
        val blockMs = block?.let { (it.untilWall - nowWall).coerceAtLeast(0L) } ?: 0L
        return when {
            block != null && blockMs > 0L && blockMs > latchedMs + REWRITE_TOLERANCE_MS -> Resolution(
                block = block,
                entry = LatchEntry(block.kind, nowElapsed + blockMs, block.untilWall, bootCount),
            )
            latchedMs > 0L && existing != null -> Resolution(
                // Блокировка по часам короче защёлки или её нет вовсе: действует защёлка.
                block = LimitBlock(if (block != null && blockMs > 0L) block.kind else existing.kind, nowWall + latchedMs),
                entry = existing,
            )
            block != null && blockMs > 0L -> Resolution(
                block = block,
                entry = LatchEntry(block.kind, nowElapsed + blockMs, block.untilWall, bootCount),
            )
            else -> Resolution(block = null, entry = null)
        }
    }

    data class Resolution(val block: LimitBlock?, val entry: LatchEntry?)
}

/**
 * Защёлки по приложениям в SharedPreferences с кэшем в памяти. Вызывается трекером из главного
 * потока раз в секунду: чтение — из кэша, запись (`apply`) — только при изменении.
 */
class LimitLatchStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cache = HashMap<TrackedApp, LatchEntry?>()

    fun get(app: TrackedApp): LatchEntry? = cache.getOrPut(app) { decode(prefs.getString(app.name, null)) }

    fun put(app: TrackedApp, entry: LatchEntry?) {
        if (get(app) == entry) return
        cache[app] = entry
        prefs.edit().apply {
            if (entry == null) remove(app.name) else putString(app.name, encode(entry))
        }.apply()
    }

    private fun encode(entry: LatchEntry): String =
        listOf(entry.kind.name, entry.untilElapsed, entry.untilWall, entry.bootCount).joinToString(SEPARATOR)

    private fun decode(raw: String?): LatchEntry? {
        val parts = raw?.split(SEPARATOR) ?: return null
        if (parts.size != 4) return null
        val kind = LimitBlock.Kind.entries.firstOrNull { it.name == parts[0] } ?: return null
        return LatchEntry(
            kind = kind,
            untilElapsed = parts[1].toLongOrNull() ?: return null,
            untilWall = parts[2].toLongOrNull() ?: return null,
            bootCount = parts[3].toIntOrNull() ?: return null,
        )
    }

    private companion object {
        const val PREFS = "detoxin_limit_latch"
        const val SEPARATOR = "|"
    }
}
