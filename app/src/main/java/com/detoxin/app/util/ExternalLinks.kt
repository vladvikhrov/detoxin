package com.detoxin.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Открытие внешних ссылок без сетевого доступа приложения: страница открывается
 * в браузере пользователя через `Intent.ACTION_VIEW`, сам Detoxin в сеть не ходит.
 */
object ExternalLinks {

    /** @return `false`, если на устройстве нет приложения для открытия ссылки. */
    fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Не найден браузер для открытия ссылки", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
