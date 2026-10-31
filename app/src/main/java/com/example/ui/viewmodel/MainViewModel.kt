package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.TweakHistoryEntity
import com.example.data.freefire.FreeFireDetector
import com.example.data.freefire.FreeFirePackage
import com.example.data.shizuku.ShizukuManager
import com.example.data.shizuku.ShizukuStatus
import com.example.data.system.DeviceDiagnostics
import com.example.data.system.DeviceSpec
import com.example.data.system.RamDiagnostic
import com.example.data.system.StorageDiagnostic
import com.example.data.system.ThermalDiagnostic
import com.example.data.tweaks.TweakCatalog
import com.example.data.tweaks.TweakExecutor
import com.example.data.tweaks.TweakItem
import com.example.overlay.GameOverlayService
import com.example.overlay.OverlayPrefs
import com.example.ui.navigation.AppScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MainUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.DISCONNECTED,
    val ramDiagnostic: RamDiagnostic? = null,
    val thermalDiagnostic: ThermalDiagnostic? = null,
    val deviceSpec: DeviceSpec = DeviceSpec("", "", "", 0, ""),
    val freeFirePackages: List<FreeFirePackage> = emptyList(),
    val selectedPackage: String? = null,
    val tweaks: List<TweakItem> = emptyList(),
    val isOptimizing: Boolean = false,
    val currentProgressText: String? = null,
    val lastOptimizationSummary: String? = null,
    val historyList: List<TweakHistoryEntity> = emptyList(),
    val appliedTweakIds: Set<String> = emptySet(),
    val busyTweakIds: Set<String> = emptySet(),
    val storageDiagnostic: StorageDiagnostic? = null,
    val batteryPercent: Int? = null,
    val overlayEnabled: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val shizukuManager = ShizukuManager(application.applicationContext)
    private val database = AppDatabase.getInstance(application.applicationContext)
    private val tweakDao = database.tweakDao()
    private val tweakExecutor = TweakExecutor(shizukuManager, tweakDao, application.packageName)

    // Serializa a execução de comandos Shizuku: evita vários processos shell em paralelo.
    private val tweakMutex = Mutex()
    @Volatile private var refreshJob: Job? = null

    private val _uiState = MutableStateFlow(
        MainUiState(overlayEnabled = OverlayPrefs.isEnabled(application.applicationContext))
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        shizukuManager.initialize()

        viewModelScope.launch {
            shizukuManager.status.collect { status ->
                _uiState.update { it.copy(shizukuStatus = status) }
                refreshTweaksList()
            }
        }

        viewModelScope.launch {
            tweakDao.getAllHistory().collect { history ->
                _uiState.update { it.copy(historyList = history) }
            }
        }

        refreshAll()
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    /** Ativa/desativa o painel flutuante. A permissão de sobreposição é tratada na UI. */
    fun setOverlayEnabled(enabled: Boolean) {
        val context = getApplication<Application>().applicationContext
        OverlayPrefs.setEnabled(context, enabled)
        _uiState.update { it.copy(overlayEnabled = enabled) }
        if (enabled) GameOverlayService.start(context, prepareAi = true) else GameOverlayService.stop(context)
    }

    /** Reconcilia o estado salvo (ex.: painel fechado pela notificação ou permissão revogada). */
    fun syncOverlayState() {
        val context = getApplication<Application>().applicationContext
        var enabled = OverlayPrefs.isEnabled(context)
        if (enabled && !android.provider.Settings.canDrawOverlays(context)) {
            OverlayPrefs.setEnabled(context, false)
            enabled = false
        }
        if (_uiState.value.overlayEnabled != enabled) {
            _uiState.update { it.copy(overlayEnabled = enabled) }
        }
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val ram = DeviceDiagnostics.readRam(context)
            val thermals = DeviceDiagnostics.readThermals(context)
            val spec = DeviceDiagnostics.readDeviceSpec()
            val packages = FreeFireDetector.detectInstalledPackages(context)
            val primaryPkg = packages.firstOrNull { it.isInstalled }?.packageName
            val shizuku = shizukuManager.queryCurrentStatus()
            val storage = DeviceDiagnostics.readStorage()
            val battery = DeviceDiagnostics.readBatteryPercent(context)

            _uiState.update {
                it.copy(
                    storageDiagnostic = storage,
                    batteryPercent = battery,
                    ramDiagnostic = ram,
                    thermalDiagnostic = thermals,
                    deviceSpec = spec,
                    freeFirePackages = packages,
                    selectedPackage = primaryPkg,
                    shizukuStatus = shizuku
                )
            }

            refreshTweaksList()
        }
    }

    fun selectFreeFirePackage(packageName: String) {
        _uiState.update { it.copy(selectedPackage = packageName) }
        refreshTweaksList()
    }

    private fun refreshTweaksList() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { reloadTweaks() }
    }

    private suspend fun reloadTweaks() = withContext(Dispatchers.IO) {
        val isShizukuActive = _uiState.value.shizukuStatus == ShizukuStatus.CONNECTED
        val targetPkg = _uiState.value.selectedPackage
        val available = TweakCatalog.getAvailableTweaks(targetPkg, isShizukuActive)
        val backups = tweakDao.getAllBackups().map { it.tweakKey }.toSet()

        _uiState.update {
            it.copy(
                tweaks = available,
                appliedTweakIds = backups
            )
        }
    }

    fun optimizeNow() {
        if (_uiState.value.isOptimizing) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isOptimizing = true,
                    currentProgressText = "Iniciando otimização…",
                    lastOptimizationSummary = null
                )
            }

            try {
                // Consulta ao binder do Shizuku fora da thread principal.
                val status = withContext(Dispatchers.IO) { shizukuManager.queryCurrentStatus() }
                if (status != ShizukuStatus.CONNECTED) {
                    _uiState.update {
                        it.copy(
                            isOptimizing = false,
                            currentProgressText = null,
                            lastOptimizationSummary = "Falha: Shizuku não está conectado com autorização concedida."
                        )
                    }
                    return@launch
                }

                // Lista montada agora com o pacote escolhido: a copiada em `tweaks` pode estar defasada.
                val targetPkg = _uiState.value.selectedPackage
                val supportedTweaks = TweakCatalog.getAvailableTweaks(targetPkg, true).filter { it.isSupported }
                var successes = 0
                var errors = 0

                for (tweak in supportedTweaks) {
                    _uiState.update { it.copy(currentProgressText = "Aplicando: ${tweak.title}…") }
                    val result = try {
                        tweakMutex.withLock { tweakExecutor.applyTweak(tweak.id, targetPkg) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (result?.isSuccess == true) {
                        successes++
                    } else {
                        errors++
                    }
                    // Respiro entre ajustes para o sistema não engasgar com comandos em sequência.
                    delay(100)
                }

                val summary = if (errors == 0) {
                    "Otimização concluída com sucesso! ($successes ajustes aplicados)"
                } else {
                    "Otimização finalizada: $successes aplicados com sucesso, $errors falhas registradas no histórico."
                }

                refreshTweaksList()
                val context = getApplication<Application>()
                // Leituras de diagnóstico fora da thread principal.
                val ram = withContext(Dispatchers.IO) { DeviceDiagnostics.readRam(context) }
                val thermals = withContext(Dispatchers.IO) { DeviceDiagnostics.readThermals(context) }
                val battery = withContext(Dispatchers.IO) { DeviceDiagnostics.readBatteryPercent(context) }
                val storage = withContext(Dispatchers.IO) { DeviceDiagnostics.readStorage() }

                _uiState.update {
                    it.copy(
                        isOptimizing = false,
                        currentProgressText = null,
                        lastOptimizationSummary = summary,
                        ramDiagnostic = ram,
                        thermalDiagnostic = thermals,
                        batteryPercent = battery,
                        storageDiagnostic = storage
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isOptimizing = false,
                        currentProgressText = null,
                        lastOptimizationSummary = "Falha: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            } finally {
                // Garante que o botão nunca fique preso em "Otimizando…".
                if (_uiState.value.isOptimizing) {
                    _uiState.update { it.copy(isOptimizing = false, currentProgressText = null) }
                }
            }
        }
    }

    /** Marca o ajuste como ocupado; retorna false se já houver uma operação em andamento para ele. */
    private fun markBusy(ids: Collection<String>): Boolean {
        var added = false
        _uiState.update { state ->
            added = ids.none { it in state.busyTweakIds }
            if (added) state.copy(busyTweakIds = state.busyTweakIds + ids) else state
        }
        return added
    }

    private fun clearBusy(ids: Collection<String>) {
        _uiState.update { it.copy(busyTweakIds = it.busyTweakIds - ids.toSet()) }
    }

    fun toggleTweak(tweakId: String) {
        if (!markBusy(listOf(tweakId))) return
        viewModelScope.launch {
            try {
                tweakMutex.withLock {
                    val isApplied = _uiState.value.appliedTweakIds.contains(tweakId)
                    val targetPkg = _uiState.value.selectedPackage

                    if (isApplied) {
                        tweakExecutor.restoreTweak(tweakId, targetPkg)
                    } else {
                        tweakExecutor.applyTweak(tweakId, targetPkg)
                    }
                }
                reloadTweaks()
            } finally {
                clearBusy(listOf(tweakId))
            }
        }
    }

    /** Aplica o ajuste sem alternar: usado por botões que só aplicam (ex.: Compilar/Recompilar). */
    fun applyTweak(tweakId: String) {
        if (!markBusy(listOf(tweakId))) return
        viewModelScope.launch {
            try {
                tweakMutex.withLock {
                    tweakExecutor.applyTweak(tweakId, _uiState.value.selectedPackage)
                }
                reloadTweaks()
            } finally {
                clearBusy(listOf(tweakId))
            }
        }
    }

    fun restoreTweak(tweakId: String) {
        if (!markBusy(listOf(tweakId))) return
        viewModelScope.launch {
            try {
                tweakMutex.withLock {
                    val targetPkg = _uiState.value.selectedPackage
                    tweakExecutor.restoreTweak(tweakId, targetPkg)
                }
                reloadTweaks()
            } finally {
                clearBusy(listOf(tweakId))
            }
        }
    }

    fun restoreAllTweaks() {
        val state = _uiState.value
        val ids = (state.appliedTweakIds - state.busyTweakIds).toList()
        if (ids.isEmpty() || !markBusy(ids)) return
        viewModelScope.launch {
            try {
                tweakMutex.withLock {
                    val targetPkg = _uiState.value.selectedPackage
                    for (id in ids) {
                        tweakExecutor.restoreTweak(id, targetPkg)
                    }
                }
                reloadTweaks()
            } finally {
                clearBusy(ids)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            tweakDao.clearHistory()
        }
    }

    fun launchFreeFire(): Boolean {
        val context = getApplication<Application>().applicationContext
        val targetPkg = _uiState.value.selectedPackage
            ?: _uiState.value.freeFirePackages.firstOrNull { it.isInstalled }?.packageName
            ?: return false
        if (_uiState.value.overlayEnabled && android.provider.Settings.canDrawOverlays(context)) {
            GameOverlayService.start(context)
        }
        return FreeFireDetector.launchGame(context, targetPkg)
    }

    override fun onCleared() {
        super.onCleared()
        shizukuManager.cleanup()
    }
}
