package com.detoxin.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.detoxin.app.R
import com.detoxin.app.ui.analytics.AnalyticsScreen
import com.detoxin.app.ui.bedtime.BedtimeSettingsScreen
import com.detoxin.app.ui.habits.HabitsScreen
import com.detoxin.app.ui.permissions.DisclosurePreferences
import com.detoxin.app.ui.permissions.PermissionsScreen

/** Вкладки нижней навигации. */
enum class AppTab(val title: String, @DrawableRes val icon: Int) {
    USAGE("Экранное время", R.drawable.ic_tab_usage),
    HABITS("Привычки", R.drawable.ic_tab_habits),
}

/**
 * Корневой каркас: [NavigationBar] снизу и содержимое выбранной вкладки.
 *
 * Вкладка хранится в `rememberSaveable` и переживает поворот экрана. Экраны вкладок имеют
 * собственные Scaffold; отступы корневого Scaffold помечаются как потреблённые
 * (`consumeWindowInsets`), поэтому внутренние Scaffold не добавляют системные отступы повторно.
 * ViewModel обеих вкладок привязаны к Activity и сохраняют состояние при переключении.
 *
 * При первом запуске вместо вкладок показывается [PermissionsScreen] (явное раскрытие
 * разрешений); позже он открывается из баннера «Защита выключена» и из «О приложении».
 * Настройки «Отбоя» открываются кнопкой с луной на вкладке «Экранное время».
 */
@Composable
fun DetoxinApp() {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.USAGE) }
    var disclosureAccepted by rememberSaveable { mutableStateOf(DisclosurePreferences.isAccepted(context)) }
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    var showBedtime by rememberSaveable { mutableStateOf(false) }

    // Первый запуск: объяснение разрешений показывается до основного интерфейса.
    if (!disclosureAccepted) {
        PermissionsScreen(
            continueLabel = "Продолжить",
            onContinue = {
                DisclosurePreferences.setAccepted(context)
                disclosureAccepted = true
            },
        )
        return
    }
    if (showPermissions) {
        BackHandler { showPermissions = false }
        PermissionsScreen(continueLabel = "Готово", onContinue = { showPermissions = false })
        return
    }
    if (showBedtime) {
        BackHandler { showBedtime = false }
        BedtimeSettingsScreen(onBack = { showBedtime = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            when (selectedTab) {
                AppTab.USAGE -> AnalyticsScreen(
                    onOpenPermissions = { showPermissions = true },
                    onOpenBedtime = { showBedtime = true },
                )
                AppTab.HABITS -> HabitsScreen(onOpenPermissions = { showPermissions = true })
            }
        }
    }
}
