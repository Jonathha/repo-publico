package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.logs.ActiveAppsCollector
import com.example.data.logs.ActiveAppsSnapshot
import com.example.data.logs.UsageAccess
import com.example.data.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ActiveAppsUiState(
    val snapshot: ActiveAppsSnapshot = ActiveAppsSnapshot(),
    val loaded: Boolean = false,
    val killing: Set<String> = emptySet(),
    val message: String? = null,
    val messageIsError: Boolean = false
)

/** Estado e coleta das telas da aba Logs (separado do MainViewModel). */
class LogsViewModel(application: Application) : AndroidViewModel(application) {

    // Sem initialize(): só consulta o estado, não registra listeners do Shizuku.
    private val shizuku = ShizukuManager(application.applicationContext)
    private val activeCollector = ActiveAppsCollector(application.applicationContext, shizuku)
    // Uma operação Shizuku por vez (coleta e encerramento não rodam em paralelo).
    private val shellMutex = Mutex()

    private val _apps = MutableStateFlow(ActiveAppsUiState())
    val apps: StateFlow<ActiveAppsUiState> = _apps.asStateFlow()

    /** Atualiza a lista periodicamente; deve ser chamado dentro de repeatOnLifecycle(STARTED). */
    suspend fun pollActiveApps() {
        while (true) {
            refreshActiveApps()
            delay(ACTIVE_POLL_MS)
        }
    }

    private suspend fun refreshActiveApps() {
        val snap = withContext(Dispatchers.IO) { shellMutex.withLock { activeCollector.collect() } }
        _apps.update { it.copy(snapshot = snap, loaded = true) }
    }

    fun killApp(packageName: String) {
        if (packageName in _apps.value.killing) return
        _apps.update { it.copy(killing = it.killing + packageName, message = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { shellMutex.withLock { activeCollector.kill(packageName) } }
            _apps.update {
                it.copy(
                    killing = it.killing - packageName,
                    message = result.message,
                    messageIsError = !result.stopped
                )
            }
            refreshActiveApps()
        }
    }

    fun dismissAppsMessage() {
        _apps.update { it.copy(message = null) }
    }

    fun isUsageAccessGranted(): Boolean = UsageAccess.isGranted(getApplication())

    companion object {
        private const val ACTIVE_POLL_MS = 3_000L
    }
}
