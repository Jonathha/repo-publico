package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.logs.ActiveAppsCollector
import com.example.data.database.AppDatabase
import com.example.data.database.PingSampleEntity
import com.example.data.logs.ActiveAppsSnapshot
import com.example.data.logs.PingBucket
import com.example.data.logs.PingMath
import com.example.data.logs.PingSampler
import com.example.data.logs.PingStats
import com.example.data.logs.UsageAccess
import com.example.data.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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

/** Janelas do histórico de ping; [bucketMs] divide a janela em períodos iguais para o gráfico. */
enum class PingRange(val label: String, val windowMs: Long, val bucketMs: Long) {
    H1("1 h", 60 * 60_000L, 5 * 60_000L),
    H6("6 h", 6 * 60 * 60_000L, 30 * 60_000L),
    H24("24 h", 24 * 60 * 60_000L, 60 * 60_000L),
    D4("4 dias", 4 * 24 * 60 * 60_000L, 4 * 60 * 60_000L)
}

data class PingUiState(
    val range: PingRange = PingRange.H1,
    val loaded: Boolean = false,
    val stats: PingStats = PingMath.stats(emptyList()),
    val buckets: List<PingBucket> = emptyList(),
    /** Última medição registrada (de qualquer janela). */
    val last: PingSampleEntity? = null
)

/** Estado e coleta das telas da aba Logs (separado do MainViewModel). */
class LogsViewModel(application: Application) : AndroidViewModel(application) {

    // Sem initialize(): só consulta o estado, não registra listeners do Shizuku.
    private val shizuku = ShizukuManager(application.applicationContext)
    private val activeCollector = ActiveAppsCollector(application.applicationContext, shizuku)
    // Uma operação Shizuku por vez (coleta e encerramento não rodam em paralelo).
    private val shellMutex = Mutex()

    private val pingDao = AppDatabase.getInstance(application.applicationContext).pingDao()
    private val pingSampler = PingSampler(pingDao)
    private val pingRange = MutableStateFlow(PingRange.H1)

    /** Estatísticas e gráfico calculados a partir das medições reais gravadas no Room. */
    val ping: StateFlow<PingUiState> = combine(pingDao.observeAll(), pingRange) { all, range ->
        val now = System.currentTimeMillis()
        val inWindow = all.filter { it.timestamp >= now - range.windowMs }
        PingUiState(
            range = range,
            loaded = true,
            stats = PingMath.stats(inWindow.map { it.rttMs }),
            buckets = PingMath.buckets(
                inWindow.map { it.timestamp to it.rttMs }, now, range.windowMs, range.bucketMs
            ),
            last = all.lastOrNull()
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PingUiState())

    fun setPingRange(range: PingRange) {
        pingRange.value = range
    }

    /** Coleta de ping; deve ser chamado dentro de repeatOnLifecycle(STARTED). */
    suspend fun runPingSampler() = pingSampler.run()

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
