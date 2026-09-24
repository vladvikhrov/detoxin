package com.detoxin.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.detoxin.app.BuildConfig
import com.detoxin.app.util.ExternalLinks

/**
 * «О приложении»: версия, манифест приватности и ссылки на исходный код и поддержку.
 * Кнопка поддержки показывается, только если адрес задан в `gradle.properties`
 * (`detoxin.supportUrl`), поэтому в сборке никогда нет «битой» ссылки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("Detoxin", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Приватность", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Приложение не имеет доступа в сеть и не собирает телеметрию. " +
                            "Привычки и экранное время хранятся только на этом устройстве.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (BuildConfig.SOURCE_URL.isNotBlank()) {
                OutlinedButton(
                    onClick = { ExternalLinks.open(context, BuildConfig.SOURCE_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Исходный код (GitHub)")
                }
            }
            if (BuildConfig.SUPPORT_URL.isNotBlank()) {
                Button(
                    onClick = { ExternalLinks.open(context, BuildConfig.SUPPORT_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Поддержать разработчика")
                }
            }
            Text(
                text = "Ссылки открываются во внешнем браузере.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
