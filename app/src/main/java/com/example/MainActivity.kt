package com.example

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.example.ui.screens.HelpScreen
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingOverlay by rememberSaveable { mutableStateOf(false) }

    // Ao voltar da tela de permissão, conclui a ativação; também reconcilia o estado salvo.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (pendingOverlay && Settings.canDrawOverlays(context)) {
                    pendingOverlay = false
                    viewModel.setOverlayEnabled(true)
                } else {
                    viewModel.syncOverlayState()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onOverlayToggle: (Boolean) -> Unit = { enable ->
        when {
            !enable -> {
                pendingOverlay = false
                viewModel.setOverlayEnabled(false)
            }
            Settings.canDrawOverlays(context) -> viewModel.setOverlayEnabled(true)
            else -> {
                pendingOverlay = true
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    // Trata o botão Voltar do sistema para retornar à tela inicial se estiver em outra tela
    BackHandler(enabled = uiState.currentScreen != AppScreen.HOME) {
        viewModel.navigateTo(AppScreen.HOME)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (uiState.currentScreen != AppScreen.SETTINGS && uiState.currentScreen != AppScreen.HELP) {
                BottomNavBar(
                    currentScreen = uiState.currentScreen,
                    onNavigate = { viewModel.navigateTo(it) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val screen = uiState.currentScreen
            key(screen) {
                val fade = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    fade.animateTo(1f, tween(durationMillis = 140, easing = LinearOutSlowInEasing))
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fade.value }
                ) {
                    when (screen) {
                        AppScreen.HOME -> HomeScreen(
                            shizukuStatus = uiState.shizukuStatus,
                            ramDiagnostic = uiState.ramDiagnostic,
                            thermalDiagnostic = uiState.thermalDiagnostic,
                            storageDiagnostic = uiState.storageDiagnostic,
                            batteryPercent = uiState.batteryPercent,
                            overlayEnabled = uiState.overlayEnabled,
                            onOverlayToggle = onOverlayToggle,
                            isOptimizing = uiState.isOptimizing,
                            currentProgressText = uiState.currentProgressText,
                            lastOptimizationSummary = uiState.lastOptimizationSummary,
                            onSettingsClick = { viewModel.navigateTo(AppScreen.SETTINGS) },
                            onHelpClick = { viewModel.navigateTo(AppScreen.HELP) },
                            onOptimizeClick = { viewModel.optimizeNow() },
                            onNavigate = { viewModel.navigateTo(it) },
                            onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                            modifier = Modifier.fillMaxSize()
                        )

                        AppScreen.FREE_FIRE -> FreeFireScreen(
                            uiState = uiState,
                            onSelectPackage = { viewModel.selectFreeFirePackage(it) },
                            onApplyTweak = { viewModel.toggleTweak(it) },
                            onRestoreTweak = { viewModel.restoreTweak(it) },
                            onLaunchGame = { viewModel.launchFreeFire() },
                            modifier = Modifier.fillMaxSize()
                        )

                        AppScreen.TWEAKS -> TweaksScreen(
                            uiState = uiState,
                            onToggleTweak = { viewModel.toggleTweak(it) },
                            onRestoreTweak = { viewModel.restoreTweak(it) },
                            onRestoreAll = { viewModel.restoreAllTweaks() },
                            modifier = Modifier.fillMaxSize()
                        )

                        AppScreen.HISTORY -> HistoryScreen(
                            historyList = uiState.historyList,
                            onClearHistory = { viewModel.clearHistory() },
                            modifier = Modifier.fillMaxSize()
                        )

                        AppScreen.SETTINGS -> SettingsScreen(
                            uiState = uiState,
                            onBackClick = { viewModel.navigateTo(AppScreen.HOME) },
                            onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                            modifier = Modifier.fillMaxSize()
                        )

                        AppScreen.HELP -> HelpScreen(
                            onBackClick = { viewModel.navigateTo(AppScreen.HOME) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
