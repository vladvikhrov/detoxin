package com.detoxin.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Scope уровня процесса для коротких записей, которые не должны отменяться вместе с
 * компонентом: например, фиксация начала разрыва защиты в `onDestroy` службы, когда её
 * собственный scope уже отменён. Живёт, пока жив процесс.
 */
object ProcessScope {
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
