package com.rx.geminipro

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.rx.geminipro.viewmodels.GeminiViewModel
import com.rx.geminipro.ui.theme.GeminiProTheme
import com.rx.geminipro.screens.GeminiProScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val geminiViewModel: GeminiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val splashDeadline = android.os.SystemClock.elapsedRealtime() + 4000
        installSplashScreen().apply {
            setKeepOnScreenCondition {
                !geminiViewModel.uiState.value.isApplicationReady && android.os.SystemClock.elapsedRealtime() < splashDeadline
            }
            setOnExitAnimationListener { screen ->
                screen.remove()
            }
        }

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            GeminiProTheme(darkTheme = true) {
                GeminiProApp(geminiViewModel)
            }
        }
    }
}

@Composable
private fun GeminiProApp(viewModel: GeminiViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    val window = (LocalActivity.current)?.window
    LaunchedEffect(uiState.isKeepScreenOn) {
        if (window != null) {
            if (uiState.isKeepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    GeminiProScreen()
}