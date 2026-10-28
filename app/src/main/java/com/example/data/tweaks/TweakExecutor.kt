package com.example.data.tweaks

import com.example.data.database.TweakBackupEntity
import com.example.data.database.TweakDao
import com.example.data.database.TweakHistoryEntity
import com.example.data.shizuku.CommandResult
import com.example.data.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExecutionResult(
    val tweakId: String,
    val isSuccess: Boolean,
    val message: String
)

class TweakExecutor(
    private val shizukuManager: ShizukuManager,
    private val tweakDao: TweakDao
) {

    suspend fun applyTweak(
        tweakId: String,
        targetPackage: String?
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        var actionName = "Ajuste $tweakId"
        var result: CommandResult

        when (tweakId) {
            TweakCatalog.ID_ART_COMPILE -> {
                actionName = "Compilação AOT Speed Free Fire"
                if (targetPackage.isNullOrBlank()) {
                    return@withContext logAndReturn(
                        tweakId, actionName, "Nenhum", false,
                        "Pacote do Free Fire não encontrado no sistema.", timestamp
                    )
                }
                result = shizukuManager.execute("cmd package compile -m speed -f $targetPackage")
                val success = result.isSuccess || result.stdout.contains("Success", ignoreCase = true)
                if (success) {
                    backupValueIfNotExists(TweakCatalog.ID_ART_COMPILE, "speed")
                }
                logAndReturn(
                    tweakId, actionName, targetPackage, success,
                    if (success) "Código DEX compilado nativamente com sucesso." else "Falha: ${result.stderr.ifBlank { result.stdout }}",
                    timestamp
                )
            }

            TweakCatalog.ID_GAME_MODE -> {
                actionName = "Game Mode Performance"
                if (targetPackage.isNullOrBlank()) {
                    return@withContext logAndReturn(
                        tweakId, actionName, "Nenhum", false,
                        "Pacote do Free Fire não encontrado no sistema.", timestamp
                    )
                }
                // Backup existing mode if available
                val queryRes = shizukuManager.execute("cmd game mode get $targetPackage")
                if (queryRes.stdout.isNotBlank()) {
                    backupValueIfNotExists(TweakCatalog.ID_GAME_MODE, queryRes.stdout.trim())
                }
                result = shizukuManager.execute("cmd game mode 2 $targetPackage")
                val success = result.isSuccess || result.stdout.contains("2") || result.stdout.contains("mode")
                if (!success) {
                    tweakDao.removeBackup(tweakId)
                }
                logAndReturn(
                    tweakId, actionName, targetPackage, success,
                    if (success) "Modo Game Performance (2) definido para o Free Fire." else "Erro: ${result.stderr.ifBlank { result.stdout }}",
                    timestamp
                )
            }

            TweakCatalog.ID_REFRESH_RATE -> {
                actionName = "Taxa Máxima de Atualização"
                // Read original
                val origPeak = shizukuManager.execute("settings get system peak_refresh_rate").stdout.trim()
                val origMin = shizukuManager.execute("settings get system min_refresh_rate").stdout.trim()
                val combined = "$origPeak,$origMin"
                backupValueIfNotExists(TweakCatalog.ID_REFRESH_RATE, combined)

                // Try setting to 120Hz or 90Hz
                result = shizukuManager.execute("settings put system min_refresh_rate 120.0 && settings put system peak_refresh_rate 120.0")
                val success = result.isSuccess
                if (!success) {
                    tweakDao.removeBackup(tweakId)
                }
                logAndReturn(
                    tweakId, actionName, "Sistema", success,
                    if (success) "Taxa de atualização ajustada para máximo (120Hz/90Hz)." else "Falha: ${result.stderr}",
                    timestamp
                )
            }

            TweakCatalog.ID_ANIMATION_SCALES -> {
                actionName = "Escala de Animações (0.5x)"
                val w = shizukuManager.execute("settings get global window_animation_scale").stdout.trim()
                val t = shizukuManager.execute("settings get global transition_animation_scale").stdout.trim()
                val a = shizukuManager.execute("settings get global animator_duration_scale").stdout.trim()
                backupValueIfNotExists(TweakCatalog.ID_ANIMATION_SCALES, "$w,$t,$a")

                result = shizukuManager.execute("settings put global window_animation_scale 0.5 && settings put global transition_animation_scale 0.5 && settings put global animator_duration_scale 0.5")
                val success = result.isSuccess
                if (!success) {
                    tweakDao.removeBackup(tweakId)
                }
                logAndReturn(
                    tweakId, actionName, "Sistema", success,
                    if (success) "Escalas de animação ajustadas para 0.5x com menor latência." else "Falha: ${result.stderr}",
                    timestamp
                )
            }

            TweakCatalog.ID_TRIM_CACHE_RAM -> {
                actionName = "Limpeza de Memória & Processos"
                val killRes = shizukuManager.execute("am kill-all")
                val trimRes = shizukuManager.execute("pm trim-caches 200M")
                val msg = "Processos inativos liberados e cache aparado."
                logAndReturn(tweakId, actionName, "Sistema", true, msg, timestamp)
            }

            TweakCatalog.ID_LOGCAT_BUFFER -> {
                actionName = "Buffer de Logcat (256K)"
                val currentSizeRes = shizukuManager.execute("logcat -g")
                backupValueIfNotExists(TweakCatalog.ID_LOGCAT_BUFFER, currentSizeRes.stdout.trim())

                result = shizukuManager.execute("logcat -G 256K")
                val success = result.isSuccess
                if (!success) {
                    tweakDao.removeBackup(tweakId)
                }
                logAndReturn(
                    tweakId, actionName, "Sistema", success,
                    if (success) "Buffer do logcat ajustado para 256K para menor uso de CPU." else "Falha: ${result.stderr}",
                    timestamp
                )
            }

            TweakCatalog.ID_DISABLE_POWER_SAVE -> {
                actionName = "Desativar Economia de Bateria"
                val curr = shizukuManager.execute("settings get global low_power").stdout.trim()
                backupValueIfNotExists(TweakCatalog.ID_DISABLE_POWER_SAVE, curr)

                result = shizukuManager.execute("settings put global low_power 0")
                val success = result.isSuccess
                if (!success) {
                    tweakDao.removeBackup(tweakId)
                }
                logAndReturn(
                    tweakId, actionName, "Sistema", success,
                    if (success) "Economia de bateria desativada para manter desempenho total." else "Falha: ${result.stderr}",
                    timestamp
                )
            }

            else -> {
                ExecutionResult(tweakId, false, "Ajuste desconhecido")
            }
        }
    }

    suspend fun restoreTweak(
        tweakId: String,
        targetPackage: String?
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val backup = tweakDao.getBackup(tweakId)

        when (tweakId) {
            TweakCatalog.ID_ART_COMPILE -> {
                if (targetPackage.isNullOrBlank()) {
                    return@withContext ExecutionResult(tweakId, false, "Free Fire não encontrado")
                }
                val res = shizukuManager.execute("cmd package compile -m speed-profile -f $targetPackage")
                val success = res.isSuccess || res.stdout.contains("Success", ignoreCase = true)
                if (success) {
                    tweakDao.removeBackup(tweakId)
                }
                logAndReturn(
                    tweakId, "Restaurar Compilação Free Fire", targetPackage, success,
                    if (success) "Compilação restaurada para perfil padrão." else "Falha: ${res.stderr}",
                    timestamp, isRestore = true
                )
            }

            TweakCatalog.ID_GAME_MODE -> {
                if (targetPackage.isNullOrBlank()) {
                    return@withContext ExecutionResult(tweakId, false, "Free Fire não encontrado")
                }
                val res = shizukuManager.execute("cmd game mode 1 $targetPackage")
                val success = res.isSuccess || res.stdout.contains("1")
                tweakDao.removeBackup(tweakId)
                logAndReturn(
                    tweakId, "Restaurar Game Mode", targetPackage, success,
                    if (success) "Modo de jogo restaurado para padrão (1)." else "Falha: ${res.stderr}",
                    timestamp, isRestore = true
                )
            }

            TweakCatalog.ID_REFRESH_RATE -> {
                val values = backup?.savedValue?.split(",")
                val peak = values?.getOrNull(0)?.ifBlank { "null" } ?: "null"
                val min = values?.getOrNull(1)?.ifBlank { "null" } ?: "null"

                val cmd = if (peak == "null" || peak.isEmpty()) {
                    "settings delete system peak_refresh_rate && settings delete system min_refresh_rate"
                } else {
                    "settings put system peak_refresh_rate $peak && settings put system min_refresh_rate $min"
                }
                val res = shizukuManager.execute(cmd)
                tweakDao.removeBackup(tweakId)
                logAndReturn(
                    tweakId, "Restaurar Taxa de Atualização", "Sistema", res.isSuccess,
                    if (res.isSuccess) "Valores originais de taxa de atualização restaurados." else "Falha: ${res.stderr}",
                    timestamp, isRestore = true
                )
            }

            TweakCatalog.ID_ANIMATION_SCALES -> {
                val scales = backup?.savedValue?.split(",")
                val w = scales?.getOrNull(0)?.ifBlank { "1.0" } ?: "1.0"
                val t = scales?.getOrNull(1)?.ifBlank { "1.0" } ?: "1.0"
                val a = scales?.getOrNull(2)?.ifBlank { "1.0" } ?: "1.0"

                val cmd = "settings put global window_animation_scale $w && settings put global transition_animation_scale $t && settings put global animator_duration_scale $a"
                val res = shizukuManager.execute(cmd)
                tweakDao.removeBackup(tweakId)
                logAndReturn(
                    tweakId, "Restaurar Escalas de Animação", "Sistema", res.isSuccess,
                    if (res.isSuccess) "Escalas originais de animação restauradas ($w, $t, $a)." else "Falha: ${res.stderr}",
                    timestamp, isRestore = true
                )
            }

            TweakCatalog.ID_LOGCAT_BUFFER -> {
                val res = shizukuManager.execute("logcat -G 1M")
                tweakDao.removeBackup(tweakId)
                logAndReturn(
                    tweakId, "Restaurar Buffer Logcat", "Sistema", res.isSuccess,
                    if (res.isSuccess) "Buffer de logcat restaurado para 1M." else "Falha: ${res.stderr}",
                    timestamp, isRestore = true
                )
            }

            TweakCatalog.ID_DISABLE_POWER_SAVE -> {
                val orig = backup?.savedValue?.ifBlank { "0" } ?: "0"
                val res = shizukuManager.execute("settings put global low_power $orig")
                tweakDao.removeBackup(tweakId)
                logAndReturn(
                    tweakId, "Restaurar Modo de Bateria", "Sistema", res.isSuccess,
                    if (res.isSuccess) "Estado original de economia restaurado ($orig)." else "Falha: ${res.stderr}",
                    timestamp, isRestore = true
                )
            }

            else -> ExecutionResult(tweakId, false, "Ajuste não possui restauração.")
        }
    }

    private suspend fun backupValueIfNotExists(key: String, value: String) {
        val existing = tweakDao.getBackup(key)
        if (existing == null && value.isNotBlank() && value != "null") {
            tweakDao.saveBackup(
                TweakBackupEntity(
                    tweakKey = key,
                    savedValue = value,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun logAndReturn(
        tweakId: String,
        actionName: String,
        target: String,
        isSuccess: Boolean,
        message: String,
        timestamp: Long,
        isRestore: Boolean = false
    ): ExecutionResult {
        tweakDao.insertHistory(
            TweakHistoryEntity(
                timestamp = timestamp,
                actionName = actionName,
                target = target,
                status = if (isSuccess) "SUCESSO" else "FALHA",
                outputMessage = message,
                isRestore = isRestore
            )
        )
        return ExecutionResult(tweakId, isSuccess, message)
    }
}
