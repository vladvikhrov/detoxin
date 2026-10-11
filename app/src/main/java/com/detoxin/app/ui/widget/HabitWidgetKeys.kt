package com.detoxin.app.ui.widget

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.action.ActionParameters

/** Ключи состояния экземпляра виджета (Glance DataStore) и параметров действия клика. */
internal object HabitWidgetKeys {
    /** Привязка `appWidgetId -> habitId`: у каждого экземпляра виджета собственный файл состояния. */
    val HabitId = longPreferencesKey("habit_id")

    /**
     * День (`epochDay`), на который виджет должен показывать статус. Запись нового значения
     * гарантирует рекомпозицию активной Glance-сессии при смене дат.
     */
    val RefreshDay = longPreferencesKey("refresh_epoch_day")

    /**
     * Метка последнего запроса обновления у виджетов без реактивного источника («Сегодня»):
     * новое значение перезапускает чтение данных в активной Glance-сессии.
     */
    val RefreshTick = longPreferencesKey("refresh_tick")

    val HabitIdParam = ActionParameters.Key<Long>("habit_id")

    /** День, который отмечает касание в виджете «Неделя привычки». */
    val EpochDayParam = ActionParameters.Key<Long>("epoch_day")
}
