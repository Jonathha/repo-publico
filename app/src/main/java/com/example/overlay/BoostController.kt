package com.example.overlay

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.example.data.database.AppDatabase
import com.example.data.freefire.FreeFireDetector
import com.example.data.shizuku.ShizukuManager
import com.example.data.shizuku.ShizukuStatus
import com.example.data.tweaks.TweakCatalog
import com.example.data.tweaks.TweakExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class BoostOption(val title: String, val description: String) {
    GAME_MODE("Modo desempenho", "Perfil de desempenho do Android no jogo"),
    REFRESH_RATE("Tela em taxa máxima", "Mantém a tela no Hz máximo; gasta mais bateria"),
    SMART_CLEAN("Limpeza inteligente", "Libera apps em 2º plano só com a RAM apertada"),
    NO_INTERRUPTIONS("Sem interrupções", "Silencia avisos na partida; volta ao sair do jogo")
}

internal enum class BoostState { OFF, BUSY, ON }

/**
 * Estado e ações reais do Boost. Tudo que muda o sistema passa pelo Shizuku e só vira "ligado"
 * quando o comando é confirmado.
 *
 * - Modo desempenho / taxa máxima: reaproveitam os ajustes já existentes (TweakExecutor), então o
 *   estado é o mesmo da aba de ajustes (backup salvo = aplicado) e desligar restaura o original.
 * - Limpeza inteligente: NÃO é periódica. Matar apps em intervalos fixos os faz reiniciar (CPU, I/O e
 *   bateria) no meio da partida. Aqui só age quando a memória realmente está apertada, com intervalo
 *   mínimo entre limpezas, e só enquanto o jogo está aberto.
 * - Sem interrupções: liga o Não perturbe (prioritárias) e devolve ao sair do jogo.
 */
internal class BoostController(
    context: Context,
    private val shizuku: ShizukuManager,
    private val scope: CoroutineScope,
    private val gamePackage: () -> String?
) {
    private val app = context.applicationContext
    private val executor = TweakExecutor(shizuku, AppDatabase.getInstance(app).tweakDao(), app.packageName)
    private val dao = AppDatabase.getInstance(app).tweakDao()
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _states = MutableStateFlow(BoostOption.values().associateWith { BoostState.OFF })
    val states: StateFlow<Map<BoostOption, BoostState>> = _states.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var gameActive = false
    private var gameModePackage: String? = null
    private var cleanJob: Job? = null
    private var graceJob: Job? = null
    private var messageJob: Job? = null

    init {
        scope.launch {
            val backups = withContext(Dispatchers.IO) { dao.getAllBackups().map { it.tweakKey }.toSet() }
            setState(BoostOption.GAME_MODE, if (TweakCatalog.ID_GAME_MODE in backups) BoostState.ON else BoostState.OFF)
            setState(BoostOption.REFRESH_RATE, if (TweakCatalog.ID_REFRESH_RATE in backups) BoostState.ON else BoostState.OFF)
            setState(BoostOption.SMART_CLEAN, if (prefs.getBoolean(KEY_CLEAN, false)) BoostState.ON else BoostState.OFF)
            // Sobrou um Não perturbe ligado por aqui (serviço encerrado no meio da partida): devolve.
            if (prefs.getBoolean(KEY_DND_OWNED, false)) {
                withContext(Dispatchers.IO) { mutex.withLock { restoreDnd() } }
            }
            syncCleanJob()
        }
    }

    fun unsupportedReason(option: BoostOption): String? = when {
        option == BoostOption.GAME_MODE && Build.VERSION.SDK_INT < Build.VERSION_CODES.S ->
            "Disponível a partir do Android 12"
        option == BoostOption.REFRESH_RATE && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
            "Disponível a partir do Android 10"
        else -> null
    }

    fun toggle(option: BoostOption) {
        val current = _states.value[option] ?: return
        if (current == BoostState.BUSY) return
        val turningOn = current == BoostState.OFF
        if (turningOn) unsupportedReason(option)?.let { say(it); return }
        setState(option, BoostState.BUSY)
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                mutex.withLock { if (turningOn) activate(option) else deactivate(option) }
            }
            // O controle só vai para o estado novo se a operação foi confirmada.
            setState(
                option,
                when {
                    error == null && turningOn -> BoostState.ON
                    error == null -> BoostState.OFF
                    turningOn -> BoostState.OFF
                    else -> BoostState.ON
                }
            )
            if (error != null) say(error)
            if (option == BoostOption.SMART_CLEAN) syncCleanJob()
        }
    }

    /** Chamado quando o jogo entra/sai de primeiro plano. */
    fun onGameActive(active: Boolean) {
        gameActive = active
        if (active) {
            graceJob?.cancel()
            graceJob = null
        } else if (_states.value[BoostOption.NO_INTERRUPTIONS] == BoostState.ON && graceJob?.isActive != true) {
            // Carência: sair do jogo por instantes (ex.: responder alguém) não derruba o ajuste.
            graceJob = scope.launch {
                delay(GRACE_MS)
                if (_states.value[BoostOption.NO_INTERRUPTIONS] == BoostState.ON) toggle(BoostOption.NO_INTERRUPTIONS)
            }
        }
        syncCleanJob()
    }

    /** Serviço encerrando: para as tarefas e devolve o que foi ligado só para a sessão. */
    fun release() {
        cleanJob?.cancel()
        graceJob?.cancel()
        messageJob?.cancel()
        if (prefs.getBoolean(KEY_DND_OWNED, false)) {
            Thread { runBlocking { mutex.withLock { restoreDnd() } } }.apply { isDaemon = true }.start()
        }
    }

    // ------------------------------------------------------------ Ativar / desativar

    private fun activate(option: BoostOption): String? {
        if (shizuku.queryCurrentStatus() != ShizukuStatus.CONNECTED) return MSG_NEED_SHIZUKU
        return when (option) {
            BoostOption.GAME_MODE -> {
                val pkg = gamePackage() ?: return MSG_NO_GAME
                val r = runBlocking { executor.applyTweak(TweakCatalog.ID_GAME_MODE, pkg) }
                if (r.isSuccess) { gameModePackage = pkg; null } else "Não foi possível ativar o modo desempenho"
            }
            BoostOption.REFRESH_RATE -> {
                val r = runBlocking { executor.applyTweak(TweakCatalog.ID_REFRESH_RATE, null, maxRefreshRate()) }
                if (r.isSuccess) null else "Não foi possível ajustar a taxa da tela"
            }
            BoostOption.SMART_CLEAN -> {
                prefs.edit().putBoolean(KEY_CLEAN, true).apply()
                null
            }
            BoostOption.NO_INTERRUPTIONS -> enableDnd()
        }
    }

    private fun deactivate(option: BoostOption): String? = when (option) {
        BoostOption.GAME_MODE -> {
            val pkg = gameModePackage ?: gamePackage()
            val r = runBlocking { executor.restoreTweak(TweakCatalog.ID_GAME_MODE, pkg) }
            if (r.isSuccess) null else "Não foi possível desativar o modo desempenho"
        }
        BoostOption.REFRESH_RATE -> {
            val r = runBlocking { executor.restoreTweak(TweakCatalog.ID_REFRESH_RATE, null) }
            if (r.isSuccess) null else "Não foi possível restaurar a taxa da tela"
        }
        BoostOption.SMART_CLEAN -> {
            prefs.edit().putBoolean(KEY_CLEAN, false).apply()
            null
        }
        BoostOption.NO_INTERRUPTIONS -> restoreDnd()
    }

    private fun maxRefreshRate(): Float? = runCatching {
        val dm = app.getSystemService(DisplayManager::class.java)
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes?.maxOfOrNull { it.refreshRate }
    }.getOrNull()

    // ---------------------------------------------------------- Sem interrupções

    private fun enableDnd(): String? {
        val prev = shizuku.execute(ZEN_GET, SHELL_TIMEOUT_MS).stdout.trim()
        if (prev.toIntOrNull() == null) return "Não foi possível ler o Não perturbe"
        if (prev != "0") return "Já existe um modo Não perturbe ativo"
        prefs.edit().putBoolean(KEY_DND_OWNED, true).apply()
        val put = shizuku.execute("settings put global zen_mode 1", SHELL_TIMEOUT_MS)
        val now = shizuku.execute(ZEN_GET, SHELL_TIMEOUT_MS).stdout.trim()
        if (put.isSuccess && now == "1") return null
        shizuku.execute("settings put global zen_mode 0", SHELL_TIMEOUT_MS)
        prefs.edit().putBoolean(KEY_DND_OWNED, false).apply()
        return "Não foi possível ativar o Não perturbe"
    }

    private fun restoreDnd(): String? {
        val now = shizuku.execute(ZEN_GET, SHELL_TIMEOUT_MS).stdout.trim()
        // Se o usuário já mudou o modo por conta própria, não mexe.
        if (now == "1") {
            shizuku.execute("settings put global zen_mode 0", SHELL_TIMEOUT_MS)
            val after = shizuku.execute(ZEN_GET, SHELL_TIMEOUT_MS).stdout.trim()
            if (after != "0") return "Não foi possível desativar o Não perturbe"
        }
        prefs.edit().putBoolean(KEY_DND_OWNED, false).apply()
        return null
    }

    // ------------------------------------------------------- Limpeza inteligente

    private fun syncCleanJob() {
        val want = gameActive && _states.value[BoostOption.SMART_CLEAN] == BoostState.ON
        if (want) {
            if (cleanJob?.isActive != true) cleanJob = scope.launch { cleanLoop() }
        } else {
            cleanJob?.cancel()
            cleanJob = null
        }
    }

    private suspend fun cleanLoop() {
        while (scope.isActive) {
            val wait = if (underMemoryPressure()) {
                cleanBackgroundApps()
                CLEAN_COOLDOWN_MS
            } else {
                CLEAN_CHECK_MS
            }
            delay(wait)
        }
    }

    private suspend fun underMemoryPressure(): Boolean = withContext(Dispatchers.IO) {
        val info = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.lowMemory || info.availMem < info.totalMem * PRESSURE_FRACTION
    }

    /** Encerra só apps de terceiros em segundo plano (`am kill` poupa o que está em uso), nunca o jogo. */
    private suspend fun cleanBackgroundApps() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val installed = shizuku.execute("pm list packages -3", CLEAN_TIMEOUT_MS)
            val running = shizuku.execute("ps -A -o NAME", CLEAN_TIMEOUT_MS)
            if (!installed.isSuccess || !running.isSuccess) return@withLock
            val keep = setOfNotNull(
                gamePackage(), app.packageName,
                FreeFireDetector.PKG_FREE_FIRE_STANDARD, FreeFireDetector.PKG_FREE_FIRE_MAX
            )
            val thirdParty = installed.stdout.lineSequence()
                .map { it.trim().removePrefix("package:") }
                .filter { it.isNotEmpty() }
                .toSet()
            val victims = running.stdout.lineSequence()
                .map { it.trim().substringBefore(':') }
                .filter { it in thirdParty && it !in keep && SAFE_PACKAGE.matches(it) }
                .distinct()
                .toList()
            if (victims.isNotEmpty()) {
                shizuku.execute(victims.joinToString("; ") { "am kill $it" }, CLEAN_TIMEOUT_MS)
            }
        }
    }

    // ------------------------------------------------------------------ Utilidades

    private fun setState(option: BoostOption, state: BoostState) {
        _states.update { it + (option to state) }
    }

    private fun say(text: String) {
        _message.value = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(MESSAGE_MS)
            if (_message.value == text) _message.value = null
        }
    }

    private companion object {
        const val PREFS = "boost_prefs"
        const val KEY_CLEAN = "smart_clean"
        const val KEY_DND_OWNED = "dnd_owned"
        const val ZEN_GET = "settings get global zen_mode"
        const val MSG_NEED_SHIZUKU = "Conecte o Shizuku para usar o Boost"
        const val MSG_NO_GAME = "Jogo não detectado"
        const val SHELL_TIMEOUT_MS = 5_000L
        const val CLEAN_TIMEOUT_MS = 8_000L
        const val CLEAN_CHECK_MS = 30_000L
        const val CLEAN_COOLDOWN_MS = 3 * 60_000L
        const val PRESSURE_FRACTION = 0.12
        const val GRACE_MS = 60_000L
        const val MESSAGE_MS = 3_500L
        val SAFE_PACKAGE = Regex("^[A-Za-z0-9_.]+$")
    }
}
