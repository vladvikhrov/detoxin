package com.detoxin.app.ui.protection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.detoxin.app.core.HardBlockedApp
import com.detoxin.app.guard.PinHasher
import com.detoxin.app.ui.guard.GuardDialog
import com.detoxin.app.ui.guard.PinField
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Действие, ожидающее прохождения барьера (PIN и, где нужно, контрольная фраза). */
private sealed interface PendingAction {
    val title: String
    val requirePhrase: Boolean

    data class Relax(val app: HardBlockedApp) : PendingAction {
        override val title = "Снять блокировку ${app.displayName}"
        override val requirePhrase = true
    }

    data object Emergency : PendingAction {
        override val title = "Экстренная разблокировка"
        override val requirePhrase = true
    }

    data object ChangePin : PendingAction {
        override val title = "Смена PIN"
        override val requirePhrase = false
    }

    data object Import : PendingAction {
        override val title = "Импорт базы"
        override val requirePhrase = false
    }
}

/**
 * Экран «Защита»: всё, чем можно ослабить Detoxin, собрано здесь и закрыто барьером.
 * Ужесточение (включить блокировку, задать PIN, завершить разблокировку) выполняется сразу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectionScreen(
    onBack: () -> Unit,
    viewModel: ProtectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var showPinSetup by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Защита") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        val current = state
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PinSection(
                pinSet = current.pinSet,
                onSetPin = { if (current.pinSet) pending = PendingAction.ChangePin else showPinSetup = true },
            )
            StrictSection(
                apps = current.apps,
                onToggle = { app, strict ->
                    if (strict) viewModel.setStrict(app, true) else pending = PendingAction.Relax(app)
                },
            )
            EmergencySection(
                remainingMs = current.emergencyRemainingMs,
                onStart = { pending = PendingAction.Emergency },
                onStop = viewModel::stopEmergency,
            )
            BackupSection(
                busy = busy,
                onExport = { exportLauncher.launch(backupFileName()) },
                onImport = { pending = PendingAction.Import },
            )
        }
    }

    pending?.let { action ->
        GuardDialog(
            title = action.title,
            requirePhrase = action.requirePhrase,
            onDismiss = { pending = null },
            onPassed = {
                pending = null
                when (action) {
                    is PendingAction.Relax -> viewModel.setStrict(action.app, false)
                    PendingAction.Emergency -> viewModel.startEmergency()
                    PendingAction.ChangePin -> showPinSetup = true
                    PendingAction.Import -> importLauncher.launch(arrayOf("*/*"))
                }
            },
        )
    }

    if (showPinSetup) {
        PinSetupDialog(
            onDismiss = { showPinSetup = false },
            onConfirm = { pin ->
                viewModel.setPin(pin)
                showPinSetup = false
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun PinSection(pinSet: Boolean, onSetPin: () -> Unit) {
    SectionCard(
        title = "Мастер-PIN",
        description = if (pinSet) {
            "PIN задан. Он спрашивается перед ослаблением «Отбоя», снятием блокировок, экстренной " +
                "разблокировкой и импортом базы. Хранится только его хэш (PBKDF2 с солью); восстановить " +
                "забытый PIN нельзя."
        } else {
            "PIN не задан: ослабление защиты закрыто только контрольной фразой. Хороший приём — попросить " +
                "близкого человека задать PIN и не сообщать его вам."
        },
    ) {
        OutlinedButton(onClick = onSetPin) { Text(if (pinSet) "Сменить PIN" else "Задать PIN") }
    }
}

@Composable
private fun StrictSection(apps: List<StrictAppUi>, onToggle: (HardBlockedApp, Boolean) -> Unit) {
    SectionCard(
        title = "Полная блокировка",
        description = "Запуск приложения сразу возвращает на главный экран, а экран его установки в магазине " +
            "закрывается. Без полной блокировки TikTok и YouTube работают с обычными лимитами.",
    ) {
        apps.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.app.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = item.strict, onCheckedChange = { onToggle(item.app, it) })
            }
        }
    }
}

@Composable
private fun EmergencySection(remainingMs: Long, onStart: () -> Unit, onStop: () -> Unit) {
    val active = remainingMs > 0L
    SectionCard(
        title = "Экстренная разблокировка",
        description = "На 15 минут снимает все блокировки и лимиты. Требует PIN и контрольную фразу, а сам " +
            "период записывается в срывы и портит ночь «Отбоя».",
    ) {
        if (active) {
            val totalSeconds = (remainingMs + 999L) / 1_000L
            Text(
                text = "Защита на паузе: осталось %d:%02d".format(totalSeconds / 60L, totalSeconds % 60L),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onStop) { Text("Вернуть защиту сейчас") }
        } else {
            OutlinedButton(
                onClick = onStart,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Разблокировать на 15 минут") }
        }
    }
}

@Composable
private fun BackupSection(busy: Boolean, onExport: () -> Unit, onImport: () -> Unit) {
    SectionCard(
        title = "Резервная копия",
        description = "База (привычки, экранное время, срывы) сохраняется в выбранный вами файл и " +
            "восстанавливается из него. Без интернета: файл никуда не отправляется. Перед записью и " +
            "восстановлением файл проверяется (PRAGMA integrity_check). Импорт заменяет текущие данные.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onExport, enabled = !busy) { Text("Экспорт") }
            OutlinedButton(onClick = onImport, enabled = !busy) { Text("Импорт") }
            if (busy) CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun PinSetupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    val valid = PinHasher.isValidPin(pin)
    val mismatch = repeat.isNotEmpty() && repeat != pin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "От ${PinHasher.MIN_LENGTH} до ${PinHasher.MAX_LENGTH} цифр. Забытый PIN восстановить нельзя.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PinField(value = pin, onValueChange = { pin = it }, label = "PIN")
                PinField(value = repeat, onValueChange = { repeat = it }, label = "Повторите PIN", isError = mismatch)
                if (mismatch) {
                    Text("PIN не совпадает", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && repeat == pin, onClick = { onConfirm(pin) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun backupFileName(): String =
    "detoxin-backup-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".db"
