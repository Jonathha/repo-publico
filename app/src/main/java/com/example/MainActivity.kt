package com.example

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.BottomNavBar
import com.example.ui.navigation.AppScreen
import com.example.ui.navigation.backTarget
import com.example.ui.screens.FreeFireScreen
import com.example.ui.screens.HelpScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.logs.ActiveAppsScreen
import com.example.ui.screens.logs.PingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TweaksScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.LogsViewModel
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
fun MainApp(
    viewModel: MainViewModel = viewModel(),
    logsViewModel: LogsViewModel = viewModel()
) {
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

    // Coleta de ping só com o app em primeiro plano; cancela ao sair de STARTED.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { logsViewModel.runPingSampler() }
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
        viewModel.navigateTo(uiState.currentScreen.backTarget())
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
            val current = uiState.currentScreen
            val currentState = rememberUpdatedState(current)
            // Estado sem currentScreen (igualdade estrutural): trocar de aba não recompõe as telas que recebem o uiState.
            val tabState by remember { derivedStateOf { uiState.copy(currentScreen = AppScreen.HOME) } }
            val mainTabs = listOf(AppScreen.HOME, AppScreen.FREE_FIRE, AppScreen.TWEAKS, AppScreen.HISTORY)

            // Abas principais ficam vivas depois de compostas: trocar de aba só mostra/esconde, sem recompor do zero.
            var composedTabs by remember { mutableStateOf(setOf(AppScreen.HOME)) }
            LaunchedEffect(Unit) {
                // Pré-compõe as demais abas em segundo plano, uma por vez, após o primeiro frame.
                delay(400)
                for (tab in mainTabs) {
                    composedTabs = composedTabs + tab
                    delay(250)
                }
            }
            LaunchedEffect(current) {
                if (current in mainTabs) composedTabs = composedTabs + current
            }

            for (tab in AppScreen.entries) {
                if (tab !in composedTabs && tab != current) continue
                key(tab) {
                    // Aba oculta é medida mas só é posicionada quando atual: não desenha nem recebe toque.
                    // A troca de aba só invalida o posicionamento, sem remedir nem recompor.
                    val visibilityModifier = remember(tab) {
                        Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                if (currentState.value == tab) placeable.place(0, 0)
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().then(visibilityModifier)) {
                        when (tab) {
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
                                uiState = tabState,
                                onSelectPackage = { viewModel.selectFreeFirePackage(it) },
                                onApplyTweak = { viewModel.toggleTweak(it) },
                                onRestoreTweak = { viewModel.restoreTweak(it) },
                                onLaunchGame = { viewModel.launchFreeFire() },
                                modifier = Modifier.fillMaxSize()
                            )

                            AppScreen.TWEAKS -> TweaksScreen(
                                uiState = tabState,
                                onToggleTweak = { viewModel.toggleTweak(it) },
                                onRestoreTweak = { viewModel.restoreTweak(it) },
                                onRestoreAll = { viewModel.restoreAllTweaks() },
                                modifier = Modifier.fillMaxSize()
                            )

                            AppScreen.HISTORY -> HistoryScreen(
                                historyList = uiState.historyList,
                                onClearHistory = { viewModel.clearHistory() },
                                onOpenLogs = { viewModel.navigateTo(it) },
                                modifier = Modifier.fillMaxSize()
                            )

                            AppScreen.LOGS_APPS -> ActiveAppsScreen(
                                viewModel = logsViewModel,
                                onBack = { viewModel.navigateTo(AppScreen.HISTORY) },
                                modifier = Modifier.fillMaxSize()
                            )

                            AppScreen.LOGS_PING -> PingScreen(
                                viewModel = logsViewModel,
                                onBack = { viewModel.navigateTo(AppScreen.HISTORY) },
                                modifier = Modifier.fillMaxSize()
                            )

                            AppScreen.SETTINGS -> SettingsScreen(
                                uiState = tabState,
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
}
