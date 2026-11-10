package com.detoxin.app.ui.share

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.detoxin.app.ui.habits.daysWord
import com.detoxin.app.ui.score.DetoxScoreGauge
import com.detoxin.app.util.formatDurationShort
import kotlinx.coroutines.launch

/**
 * Экран карточки итогов: предпросмотр и «Сохранить в галерею».
 *
 * Карточка — обычный Compose-компонент ([ShareCard]). Для сохранения её содержимое записывается
 * в графический слой (`GraphicsLayer.record`) и превращается в Bitmap — тот же рендер, что на
 * экране, без повторной вёрстки и без сторонних библиотек.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCardScreen(
    onBack: () -> Unit,
    viewModel: ShareCardViewModel = viewModel(),
) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val cardLayer = rememberGraphicsLayer()
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Карточка итогов") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SharePeriod.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = item == period,
                        onClick = { viewModel.selectPeriod(item) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SharePeriod.entries.size),
                    ) { Text(item.title) }
                }
            }

            val current = stats
            if (current == null) {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                ShareCard(
                    stats = current,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            cardLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(cardLayer)
                        },
                )
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val bitmap = cardLayer.toImageBitmap().asAndroidBitmap()
                            val name = ShareCardSaver.savePng(context, bitmap)
                            saving = false
                            snackbar.showSnackbar(
                                if (name != null) "Сохранено в галерею: Pictures/Detoxin/$name" else "Не удалось сохранить карточку"
                            )
                        }
                    },
                ) { Text(if (saving) "Сохранение…" else "Сохранить в галерею") }
                Text(
                    "Карточка сохраняется на устройстве как PNG. Detoxin никуда её не отправляет — " +
                        "поделиться можно из галереи.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Карточка итогов. Цвета заданы явно и не зависят от темы и динамической палитры: сохранённая
 * картинка выглядит одинаково на любом устройстве.
 */
@Composable
fun ShareCard(stats: ShareCardStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(CardTop, CardBottom)))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("DETOXIN", color = CardAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(stats.period.caption, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(stats.dateRange, color = CardMuted, fontSize = 14.sp)
            }
        }

        DetoxScoreGauge(
            score = stats.score,
            modifier = Modifier.fillMaxWidth(0.72f).align(Alignment.CenterHorizontally),
            trackColor = CardTrack,
            needleColor = Color.White,
            numberStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, color = Color.White),
        )
        Text(
            "Detox Score",
            color = CardMuted,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        val saved = stats.savedMs
        if (saved != null) {
            CardStat(value = formatDurationShort(saved), label = "сэкономлено по сравнению с прошлым периодом")
        } else {
            CardStat(value = formatDurationShort(stats.screenTimeMs), label = "экранного времени в отслеживаемых приложениях")
        }
        CardStat(
            value = stats.blockedShorts.toString(),
            label = "отбито попыток открыть Shorts" +
                if (stats.blockedTotal > stats.blockedShorts) " · всего блокировок: ${stats.blockedTotal}" else "",
        )
        CardStat(
            value = "${stats.bestStreak} ${daysWord(stats.bestStreak)}",
            label = "лучший действующий стрик привычки",
        )
        Spacer(Modifier.height(2.dp))
        Text("Сделано офлайн · 0 КБ сетевого трафика", color = CardMuted, fontSize = 11.sp)
    }
}

@Composable
private fun CardStat(value: String, label: String) {
    Column {
        Text(value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(label, color = CardMuted, fontSize = 14.sp)
    }
}

private val CardTop = Color(0xFF0F2A22)
private val CardBottom = Color(0xFF101418)
private val CardAccent = Color(0xFF8AD6A8)
private val CardMuted = Color(0xFFA9B4BF)
private val CardTrack = Color(0xFF26323A)
