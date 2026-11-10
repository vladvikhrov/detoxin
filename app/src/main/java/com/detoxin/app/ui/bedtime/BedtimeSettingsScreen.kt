package com.detoxin.app.ui.bedtime

import com.detoxin.app.ui.guard.GuardDialog
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.detoxin.app.bedtime.BedtimeNotifications
import com.detoxin.app.usage.BedtimePolicy
import com.detoxin.app.usage.BedtimeSettings
import com.detoxin.app.util.formatMinuteOfDay
import kotlin.math.roundToInt

/**
 * Настройки режима «Отбой»: окно сна, ночной лимит и правило «ночью только строже».
 *
 * Изменения собираются в черновик и применяются кнопкой «Сохранить»: так ночью одно ослабление
 * не превращается в серию промежуточных отложенных состояний. Если черновик мягче действующих
 * настроек, а сейчас «замок», кнопка прямо говорит, что изменение начнёт действовать утром.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedtimeSettingsScreen(
    onBack: () -> Unit,
    viewModel: BedtimeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Ослабление «Отбоя» при заданном мастер-PIN сначала проходит через барьер.
    var guarded by remember { mutableStateOf<BedtimeSettings?>(null) }
    guarded?.let { requested ->
        GuardDialog(
            title = "Ослабление «Отбоя»",
            requirePhrase = false,
            onDismiss = { guarded = null },
            onPassed = {
                guarded = null
                viewModel.save(requested)
            },
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Режим «Отбой»") },
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
        } else {
            BedtimeForm(
                state = current,
                onSave = { requested ->
                    if (BedtimePolicy.isRelaxation(current.settings, requested)) guarded = requested else viewModel.save(requested)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BedtimeForm(
    state: BedtimeUiState,
    onSave: (BedtimeSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Черновик начинается с действующих настроек и сбрасывается, когда они меняются (сохранение, утро).
    var enabled by rememberSaveable(state.settings) { mutableStateOf(state.settings.enabled) }
    var startMinute by rememberSaveable(state.settings) { mutableStateOf(state.settings.startMinute) }
    var wakeMinute by rememberSaveable(state.settings) { mutableStateOf(state.settings.wakeMinute) }
    var allowance by rememberSaveable(state.settings) { mutableStateOf(state.settings.nightAllowanceMinutes) }
    var editing by rememberSaveable { mutableStateOf<TimeField?>(null) }
    var notificationsAllowed by remember { mutableStateOf(BedtimeNotifications.canPost(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted && BedtimeNotifications.canPost(context)
    }

    val draft = BedtimeSettings(enabled, startMinute, wakeMinute, allowance)
    val changed = draft != state.settings
    val deferred = state.lockedUntilLabel != null && BedtimePolicy.isRelaxation(state.settings, draft)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Ночью TikTok и YouTube закрываются до утра, а дневной лимит не обнуляется в полночь. " +
                "Shorts, «Клипы» и «Лента» блокируются круглосуточно, как и днём.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Включить «Отбой»", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeButton(
                label = "Отбой",
                minuteOfDay = startMinute,
                enabled = enabled,
                onClick = { editing = TimeField.START },
                modifier = Modifier.weight(1f),
            )
            TimeButton(
                label = "Подъём",
                minuteOfDay = wakeMinute,
                enabled = enabled,
                onClick = { editing = TimeField.WAKE },
                modifier = Modifier.weight(1f),
            )
        }
        if (startMinute == wakeMinute) {
            Text(
                "Время отбоя и подъёма совпадают — выберите разное время.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Column {
            Text(
                text = if (allowance == 0) {
                    "Ночной лимит: 0 мин — блокировка сразу при открытии"
                } else {
                    "Ночной лимит: $allowance мин на TikTok и YouTube вместе"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = allowance.toFloat(),
                onValueChange = { allowance = it.roundToInt() },
                valueRange = 0f..BedtimeSettings.MAX_ALLOWANCE_MINUTES.toFloat(),
                steps = BedtimeSettings.MAX_ALLOWANCE_MINUTES - 1,
                enabled = enabled,
            )
        }

        LockCard(state)

        if (enabled && !notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InfoCard(
                title = "Уведомления выключены",
                text = "Без них не придут напоминание за 30 минут до отбоя и итог, если защиту выключат.",
                actionLabel = "Разрешить уведомления",
                onAction = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }

        Button(
            onClick = {
                onSave(draft)
                if (draft.enabled && !notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            enabled = changed && draft.hasWindow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (deferred) "Сохранить (ослабление — с ${state.lockedUntilLabel})" else "Сохранить")
        }

        InfoCard(
            title = "Если выключить защиту",
            text = "Android позволяет выключить службу Detoxin в любой момент. Detoxin это запомнит: после " +
                "включения придёт итог «Защита была выключена 23:47–02:10, TikTok: 1 ч 52 мин», а ночь не " +
                "засчитается в привычку «Лёг вовремя». Время берётся из системной статистики Android.",
        )
        Spacer(Modifier.height(8.dp))
    }

    editing?.let { field ->
        TimePickerDialog(
            title = if (field == TimeField.START) "Время отбоя" else "Время подъёма",
            initialMinute = if (field == TimeField.START) startMinute else wakeMinute,
            onDismiss = { editing = null },
            onConfirm = { minute ->
                if (field == TimeField.START) startMinute = minute else wakeMinute = minute
                editing = null
            },
        )
    }
}

@Composable
private fun LockCard(state: BedtimeUiState) {
    val pending = state.pending
    when {
        pending != null -> InfoCard(
            title = "Запланировано на ${state.pendingFromLabel}",
            text = describe(pending) + ". До этого действуют текущие, более строгие настройки.",
        )
        state.lockedUntilLabel != null -> InfoCard(
            title = "Сейчас можно только ужесточить",
            text = "Идёт отбой или до него меньше 30 минут. Раньше отбой, позже подъём или меньший лимит " +
                "применяются сразу; выключение и послабления — только с ${state.lockedUntilLabel}.",
        )
    }
}

@Composable
private fun TimeButton(
    label: String,
    minuteOfDay: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(formatMinuteOfDay(minuteOfDay), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (minuteOfDay: Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun InfoCard(
    title: String,
    text: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun describe(settings: BedtimeSettings): String =
    if (!settings.enabled) {
        "«Отбой» будет выключен"
    } else {
        "«Отбой» ${formatMinuteOfDay(settings.startMinute)}–${formatMinuteOfDay(settings.wakeMinute)}, " +
            "ночной лимит ${settings.nightAllowanceMinutes} мин"
    }

private enum class TimeField { START, WAKE }
