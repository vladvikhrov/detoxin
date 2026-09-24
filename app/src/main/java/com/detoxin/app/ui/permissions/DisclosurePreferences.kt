package com.detoxin.app.ui.permissions

import android.content.Context

/**
 * Факт того, что пользователь прочитал объяснение разрешений. Хранится локально
 * (SharedPreferences), влияет только на то, показывать ли экран при запуске.
 */
object DisclosurePreferences {

    private const val FILE = "detoxin_onboarding"
    private const val KEY_ACCEPTED = "permissions_disclosure_accepted_v1"

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    fun setAccepted(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACCEPTED, true).apply()
    }
}
