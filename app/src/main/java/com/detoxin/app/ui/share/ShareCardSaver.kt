package com.detoxin.app.ui.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Сохранение карточки в галерею: PNG в `Pictures/Detoxin` через MediaStore.
 *
 * На Android 10+ (minSdk 29) запись в собственную запись MediaStore не требует разрешений на
 * хранилище. Файл создаётся локально и никуда не отправляется. Флаг `IS_PENDING` скрывает
 * файл от галереи, пока он не записан целиком; при ошибке незавершённая запись удаляется.
 */
object ShareCardSaver {

    private const val TAG = "ShareCardSaver"
    private const val FOLDER = "Detoxin"

    /** @return имя сохранённого файла или `null`, если сохранить не удалось. */
    suspend fun savePng(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val name = "detoxin-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: RuntimeException) {
            Log.e(TAG, "MediaStore insert failed", e)
            null
        } ?: return@withContext null

        // Аппаратный Bitmap (результат записи слоя Compose) нельзя сжимать напрямую на всех версиях.
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("No output stream for $uri")
            stream.use { output ->
                if (!software.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("PNG encoding failed")
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        } catch (e: IOException) {
            Log.e(TAG, "Unable to write share card", e)
            resolver.delete(uri, null, null)
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to write share card", e)
            resolver.delete(uri, null, null)
            null
        } finally {
            if (software !== bitmap) software.recycle()
        }
    }
}
