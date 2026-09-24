package com.detoxin.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B7F4B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F3C3),
    onPrimaryContainer = Color(0xFF002111),
    secondary = Color(0xFF4F6354),
    tertiary = Color(0xFF3B6470),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD6A8),
    onPrimary = Color(0xFF003920),
    primaryContainer = Color(0xFF005230),
    onPrimaryContainer = Color(0xFFA6F3C3),
    secondary = Color(0xFFB6CCB9),
    tertiary = Color(0xFFA3CDDB),
)

/** Material 3 тема: динамические цвета на Android 12+, фирменная зелёная схема на API 29–30. */
@Composable
fun DetoxinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
