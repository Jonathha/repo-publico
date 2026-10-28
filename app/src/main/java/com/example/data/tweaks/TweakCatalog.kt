package com.example.data.tweaks

import android.os.Build

object TweakCatalog {

    const val ID_ART_COMPILE = "tweak_art_compile"
    const val ID_GAME_MODE = "tweak_game_mode"
    const val ID_REFRESH_RATE = "tweak_refresh_rate"
    const val ID_ANIMATION_SCALES = "tweak_animation_scales"
    const val ID_TRIM_CACHE_RAM = "tweak_trim_cache_ram"
    const val ID_LOGCAT_BUFFER = "tweak_logcat_buffer"
    const val ID_DISABLE_POWER_SAVE = "tweak_disable_power_save"

    fun getAvailableTweaks(
        targetPackage: String?,
        isShizukuActive: Boolean
    ): List<TweakItem> {
        val hasPackage = !targetPackage.isNullOrBlank()

        return listOf(
            TweakItem(
                id = ID_ART_COMPILE,
                title = "Compilação AOT Speed do Free Fire",
                description = "Compila os arquivos DEX do Free Fire em código nativo Ahead-of-Time (speed). Elimina travamentos de compilação JIT durante combates.",
                technicalDetails = "Executa: cmd package compile -m speed -f $targetPackage",
                category = TweakCategory.PERFORMANCE,
                requiresShizuku = true,
                minSdk = 24,
                isSupported = hasPackage && isShizukuActive,
                unsupportedReason = when {
                    !hasPackage -> "Nenhuma instalação do Free Fire detectada"
                    !isShizukuActive -> "Requer Shizuku conectado e autorizado"
                    else -> null
                },
                isReversible = true
            ),
            TweakItem(
                id = ID_GAME_MODE,
                title = "Modo de Jogo Nativo (Performance)",
                description = "Ativa o perfil de alto desempenho na API GameService do Android para o Free Fire, priorizando taxa de quadros e CPU.",
                technicalDetails = "Executa: cmd game mode 2 $targetPackage (Android 12+)",
                category = TweakCategory.PERFORMANCE,
                requiresShizuku = true,
                minSdk = 31,
                isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasPackage && isShizukuActive,
                unsupportedReason = when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "Disponível apenas a partir do Android 12 (API 31)"
                    !hasPackage -> "Nenhuma instalação do Free Fire detectada"
                    !isShizukuActive -> "Requer Shizuku conectado e autorizado"
                    else -> null
                },
                isReversible = true
            ),
            TweakItem(
                id = ID_REFRESH_RATE,
                title = "Forçar Taxa Máxima de Atualização",
                description = "Sincroniza a taxa mínima e máxima de atualização para a maior frequência suportada pela tela, evitando quedas de Hz.",
                technicalDetails = "Ajusta peak_refresh_rate e min_refresh_rate em settings system",
                category = TweakCategory.DISPLAY,
                requiresShizuku = true,
                minSdk = 29,
                isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isShizukuActive,
                unsupportedReason = when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "Disponível a partir do Android 10 (API 29)"
                    !isShizukuActive -> "Requer Shizuku conectado e autorizado"
                    else -> null
                },
                isReversible = true
            ),
            TweakItem(
                id = ID_ANIMATION_SCALES,
                title = "Reduzir Escalas de Animação do Sistema",
                description = "Reduz as escalas de transição e animação para 0.5x, acelerando a resposta de toque e economizando renderização de UI.",
                technicalDetails = "Ajusta window_animation_scale, transition_animation_scale e animator_duration_scale",
                category = TweakCategory.LATENCY,
                requiresShizuku = true,
                minSdk = 24,
                isSupported = isShizukuActive,
                unsupportedReason = if (!isShizukuActive) "Requer Shizuku conectado e autorizado" else null,
                isReversible = true
            ),
            TweakItem(
                id = ID_TRIM_CACHE_RAM,
                title = "Liberar RAM & Processos em Segundo Plano",
                description = "Libera processos inativos e aciona limpeza de cache do sistema antes de iniciar a partida, garantindo mais memória para o jogo.",
                technicalDetails = "Executa: am kill-all e solicitação de trim de cache",
                category = TweakCategory.MEMORY,
                requiresShizuku = true,
                minSdk = 24,
                isSupported = isShizukuActive,
                unsupportedReason = if (!isShizukuActive) "Requer Shizuku conectado e autorizado" else null,
                isReversible = false
            ),
            TweakItem(
                id = ID_LOGCAT_BUFFER,
                title = "Otimizar Buffer de Logcat do Sistema",
                description = "Limita o tamanho do buffer de logs do sistema para 256K, reduzindo overhead de I/O e ciclo de CPU dos serviços de log.",
                technicalDetails = "Executa: logcat -G 256K com backup do tamanho anterior",
                category = TweakCategory.MEMORY,
                requiresShizuku = true,
                minSdk = 24,
                isSupported = isShizukuActive,
                unsupportedReason = if (!isShizukuActive) "Requer Shizuku conectado e autorizado" else null,
                isReversible = true
            ),
            TweakItem(
                id = ID_DISABLE_POWER_SAVE,
                title = "Desativar Economia de Bateria Limitadora",
                description = "Garante que o modo de economia de energia não limite a velocidade do processador ou trave o display em 60Hz durante o jogo.",
                technicalDetails = "Verifica e ajusta settings put global low_power 0",
                category = TweakCategory.PERFORMANCE,
                requiresShizuku = true,
                minSdk = 24,
                isSupported = isShizukuActive,
                unsupportedReason = if (!isShizukuActive) "Requer Shizuku conectado e autorizado" else null,
                isReversible = true
            )
        )
    }
}
