package com.detoxin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.detoxin.app.ui.navigation.DetoxinApp
import com.detoxin.app.ui.theme.DetoxinTheme

/** Хост Compose-интерфейса: корневой каркас с нижней навигацией [DetoxinApp]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DetoxinTheme {
                DetoxinApp()
            }
        }
    }
}
