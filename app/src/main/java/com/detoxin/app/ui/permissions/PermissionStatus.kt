package com.detoxin.app.ui.permissions

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.detoxin.app.service.DetoxinAccessibilityService
import com.detoxin.app.usage.UsageStatsReader

/** Текущее состояние системных разрешений, от которых зависит защита. */
data class PermissionStatus(
    val accessibilityEnabled: Boolean,
    val overlayGranted: Boolean,
    val usageAccessGranted: Boolean,
) {
    val allGranted: Boolean get() = accessibilityEnabled && overlayGranted && usageAccessGranted
}

/**
 * Проверка разрешений и переходы в системные настройки.
 * Разрешения выдаёт только пользователь в настройках Android: приложение их не запрашивает
 * диалогом, а лишь открывает нужный экран после явного объяснения.
 */
object PermissionChecker {

    fun status(context: Context) = PermissionStatus(
        accessibilityEnabled = isAccessibilityServiceEnabled(context),
        overlayGranted = Settings.canDrawOverlays(context),
        usageAccessGranted = UsageStatsReader.hasAccess(context),
    )

    /** Служба включена, если её компонент есть в списке `ENABLED_ACCESSIBILITY_SERVICES`. */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DetoxinAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    /**
     * Экран «Специальные возможности». Дополнительные аргументы подсвечивают службу Detoxin
     * на прошивках, которые их поддерживают; остальные просто открывают общий список.
     */
    fun openAccessibilitySettings(context: Context) {
        val component = ComponentName(context, DetoxinAccessibilityService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) })
        startSafely(context, intent)
    }

    /** «Доступ к истории использования»: список приложений, где пользователь включает Detoxin. */
    fun openUsageAccessSettings(context: Context) {
        startSafely(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        startSafely(context, intent)
    }

    /** Сведения о приложении: здесь на Android 13+ находится «Разрешить ограниченные настройки». */
    fun openAppDetails(context: Context) {
        startSafely(context, appDetailsIntent(context))
    }

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun startSafely(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
}

/** Статус разрешений, перепроверяемый при каждом возврате на экран (например, из настроек). */
@Composable
fun rememberPermissionStatus(): PermissionStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(PermissionChecker.status(context)) }
    LifecycleResumeEffect(context) {
        status = PermissionChecker.status(context)
        onPauseOrDispose { }
    }
    return status
}
