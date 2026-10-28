package com.example

import android.os.Bundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.BottomNavBar
import com.example.ui.navigation.AppScreen
import com.example.ui.screens.FreeFireScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TweaksScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Trata o botão Voltar do sistema para retornar à tela inicial se estiver em outra tela
    BackHandler(enabled = uiState.currentScreen != AppScreen.HOME) {
        viewModel.navigateTo(AppScreen.HOME)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (uiState.currentScreen != AppScreen.SETTINGS) {
                BottomNavBar(
                    currentScreen = uiState.currentScreen,
                    onNavigate = { viewModel.navigateTo(it) }
                )
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        AnimatedContent(
            targetState = uiState.currentScreen,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "screenTransition"
        ) { screen ->
            when (screen) {
                AppScreen.HOME -> HomeScreen(
                    shizukuStatus = uiState.shizukuStatus,
                    ramDiagnostic = uiState.ramDiagnostic,
                    thermalDiagnostic = uiState.thermalDiagnostic,
                    isOptimizing = uiState.isOptimizing,
                    currentProgressText = uiState.currentProgressText,
                    lastOptimizationSummary = uiState.lastOptimizationSummary,
                    onSettingsClick = { viewModel.navigateTo(AppScreen.SETTINGS) },
                    onOptimizeClick = { viewModel.optimizeNow() },
                    onNavigate = { viewModel.navigateTo(it) },
                    onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                    modifier = contentModifier
                )

                AppScreen.FREE_FIRE -> FreeFireScreen(
                    uiState = uiState,
                    onSelectPackage = { viewModel.selectFreeFirePackage(it) },
                    onApplyTweak = { viewModel.toggleTweak(it) },
                    onRestoreTweak = { viewModel.restoreTweak(it) },
                    onLaunchGame = { viewModel.launchFreeFire() },
                    modifier = contentModifier
                )

                AppScreen.TWEAKS -> TweaksScreen(
                    uiState = uiState,
                    onToggleTweak = { viewModel.toggleTweak(it) },
                    onRestoreTweak = { viewModel.restoreTweak(it) },
                    onRestoreAll = { viewModel.restoreAllTweaks() },
                    modifier = contentModifier
                )

                AppScreen.HISTORY -> HistoryScreen(
                    historyList = uiState.historyList,
                    onClearHistory = { viewModel.clearHistory() },
                    modifier = contentModifier
                )

                AppScreen.SETTINGS -> SettingsScreen(
                    uiState = uiState,
                    onBackClick = { viewModel.navigateTo(AppScreen.HOME) },
                    onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                    modifier = contentModifier
                )
            }
        }
    }
}
