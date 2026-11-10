package com.detoxin.app.ui.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.detoxin.app.guard.GuardRepository
import com.detoxin.app.guard.GuardState
import com.detoxin.app.guard.PinCheck
import com.detoxin.app.guard.PinHasher
import com.detoxin.app.guard.SobrietyPhrase
import kotlinx.coroutines.launch

/**
 * Барьер перед ослаблением защиты: мастер-PIN (если задан), затем — при [requirePhrase] —
 * «тест на трезвость»: фразу нужно набрать вручную, вставка из буфера обмена отключена.
 *
 * [onPassed] вызывается ровно один раз, когда все шаги пройдены. Если PIN не задан и фраза не
 * требуется, барьер пропускает сразу.
 */
@Composable
fun GuardDialog(
    title: String,
    requirePhrase: Boolean,
    onPassed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val guard by remember { GuardRepository.flow(context) }.collectAsState(initial = null)
    val state = guard ?: return
    var pinPassed by rememberSaveable { mutableStateOf(false) }
    val passed by rememberUpdatedState(onPassed)

    val needPin = state.pinSet && !pinPassed
    when {
        needPin -> PinStep(
            title = title,
            onVerified = { pinPassed = true },
            onDismiss = onDismiss,
        )
        requirePhrase -> PhraseStep(title = title, onConfirmed = { passed() }, onDismiss = onDismiss)
        else -> LaunchedEffect(Unit) { passed() }
    }
}

@Composable
private fun PinStep(title: String, onVerified: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Введите мастер-PIN Detoxin.", style = MaterialTheme.typography.bodyMedium)
                PinField(value = pin, onValueChange = { pin = it; error = null }, label = "PIN", isError = error != null)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !checking && PinHasher.isValidPin(pin),
                onClick = {
                    checking = true
                    scope.launch {
                        when (val check = GuardRepository.verifyPin(context, pin)) {
                            PinCheck.Ok -> onVerified()
                            is PinCheck.Wrong -> error = "Неверный PIN. Осталось попыток: ${check.attemptsLeft}"
                            is PinCheck.LockedOut ->
                                error = "Слишком много попыток. Повторите через ${(check.remainingMs + 59_999L) / 60_000L} мин"
                        }
                        pin = ""
                        checking = false
                    }
                },
            ) { Text("Далее") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun PhraseStep(title: String, onConfirmed: () -> Unit, onDismiss: () -> Unit) {
    // Намеренно remember, а не rememberSaveable: поворот экрана или уход из приложения
    // начинают ввод заново.
    var typed by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    val correct = SobrietyPhrase.correctPrefixLength(typed)
    val hasTypo = correct < typed.trimEnd().length

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Чтобы продолжить, наберите фразу вручную — буква в букву, с заглавной и запятыми:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("«${SobrietyPhrase.TEXT}»", style = MaterialTheme.typography.titleSmall)
                CompositionLocalProvider(LocalTextToolbar provides NoActionsTextToolbar) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { next ->
                            if (SobrietyPhrase.acceptsEdit(typed, next)) {
                                typed = next
                                rejected = false
                            } else {
                                rejected = true
                            }
                        },
                        isError = hasTypo,
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                        supportingText = {
                            Text(
                                when {
                                    rejected -> "Вставка и подстановка слов отключены — набирайте по одной букве"
                                    hasTypo -> "Опечатка после ${correct}-го символа"
                                    else -> "$correct из ${SobrietyPhrase.TEXT.length}"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = SobrietyPhrase.matches(typed), onClick = onConfirmed) { Text("Подтвердить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Поле PIN: только цифры, скрытый ввод. */
@Composable
fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onValueChange(next.filter { it in '0'..'9' }.take(PinHasher.MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Панель действий с текстом, которая никогда не показывается: в поле фразы нет «Вставить»,
 * «Копировать» и «Выбрать всё». Вставку через клавиатуру отсекает [SobrietyPhrase.acceptsEdit].
 */
private object NoActionsTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun hide() = Unit

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
}

/** Требуется ли вообще что-то подтверждать (для подписей кнопок). */
fun GuardState.requiresPin(): Boolean = pinSet
