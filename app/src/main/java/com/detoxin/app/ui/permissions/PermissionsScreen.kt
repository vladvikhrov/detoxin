package com.detoxin.app.ui.permissions

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Явное раскрытие (prominent disclosure) перед выдачей чувствительных разрешений.
 *
 * Пользователь сначала читает, что именно видит служба специальных возможностей и чего
 * приложение не делает, и только затем кнопкой переходит в системные настройки — это
 * требование модерации магазинов для приложений с AccessibilityService.
 * Статус обновляется при возврате из настроек.
 */
@Composable
fun PermissionsScreen(
    onContinue: () -> Unit,
    continueLabel: String,
) {
    val context = LocalContext.current
    val status = rememberPermissionStatus()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Как работает защита", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Detoxin закрывает бесконечные ленты и считает время в TikTok и YouTube. " +
                    "Для этого нужны три системных разрешения. Их выдаёте только вы в настройках Android, " +
                    "и отозвать их можно в любой момент.",
                style = MaterialTheme.typography.bodyLarge,
            )

            PermissionCard(
                title = "Служба специальных возможностей",
                granted = status.accessibilityEnabled,
                explanation = listOf(
                    "Что видит: содержимое экрана только в YouTube, ВКонтакте и TikTok — названия вкладок " +
                        "и элементов интерфейса, чтобы распознать Shorts, «Клипы» и «Ленту».",
                    "Что делает: нажимает системную кнопку «Назад», когда открыт один из этих разделов, " +
                        "и показывает экран лимита. Пока открыты TikTok или YouTube, раз в секунду проверяет, " +
                        "какое приложение на экране, — узнаёт только его системное имя, без содержимого.",
                    "Что сохраняет: только тип блокировки и время. " +
                        "Тексты сообщений, комментарии, видео и данные других приложений не читаются и не сохраняются.",
                ),
                actionLabel = if (status.accessibilityEnabled) "Открыть настройки" else "Включить службу",
                onAction = { PermissionChecker.openAccessibilitySettings(context) },
            )

            PermissionCard(
                title = "Доступ к истории использования",
                granted = status.usageAccessGranted,
                explanation = listOf(
                    "Что даёт: точное экранное время TikTok и YouTube из системной статистики Android — " +
                        "тех же данных, что показывает «Цифровое благополучие». Время не теряется, " +
                        "даже если телефон остановит службу ради экономии батареи.",
                    "Что использует: только сколько и когда были открыты TikTok и YouTube, чтобы считать лимиты " +
                        "(TikTok — 15 минут в час и 1 час в день, YouTube — 1.5 часа в день) и строить графики.",
                    "Без этого доступа экранное время и лимиты не работают; блокировка Shorts и клипов работает.",
                ),
                actionLabel = if (status.usageAccessGranted) "Открыть настройки" else "Разрешить",
                onAction = { PermissionChecker.openUsageAccessSettings(context) },
            )

            PermissionCard(
                title = "Показ поверх других приложений",
                granted = status.overlayGranted,
                explanation = listOf(
                    "Нужен, чтобы показать экран отдыха поверх TikTok или YouTube, когда исчерпан дневной лимит.",
                    "Без этого разрешения экран отдыха всё равно появится через службу специальных возможностей, " +
                        "но на некоторых прошивках может работать нестабильно.",
                ),
                actionLabel = if (status.overlayGranted) "Открыть настройки" else "Разрешить",
                onAction = { PermissionChecker.openOverlaySettings(context) },
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !status.accessibilityEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Пункт службы недоступен?", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "На Android 13+ для приложений не из магазина откройте сведения о приложении → " +
                                "⋮ (вверху справа) → «Разрешить ограниченные настройки», затем включите службу снова.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { PermissionChecker.openAppDetails(context) }) {
                            Text("Открыть сведения о приложении")
                        }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Чего Detoxin не делает", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "• Не имеет доступа в интернет — у приложения нет разрешения INTERNET.\n" +
                            "• Не собирает телеметрию, рекламные идентификаторы и аналитику.\n" +
                            "• Не передаёт данные третьим лицам: всё хранится только на этом устройстве " +
                            "и удаляется вместе с приложением.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.size(4.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(continueLabel)
            }
            if (!status.allGranted) {
                Text(
                    text = "Можно продолжить без разрешений: привычки и виджет работают и так, " +
                        "а защиту включить позже из раздела «О приложении».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    explanation: List<String>,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (granted) "Включено" else "Выключено",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            explanation.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            FilledTonalButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}
