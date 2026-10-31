package com.example.data.system

import com.example.data.shizuku.ShizukuManager
import com.example.data.shizuku.ShizukuStatus

/**
 * FPS do jogo medido nos quadros que a camada dele entrega ao SurfaceFlinger
 * (`dumpsys SurfaceFlinger --latency`). Exige Shizuku: um app comum não enxerga isso.
 * Devolve nulo quando não dá para medir (sem Shizuku, camada não encontrada ou sem dados).
 */
class GameFpsMeter(private val shizuku: ShizukuManager) {

    private var layerPackage: String? = null
    private var layerName: String? = null
    private var lastNewest = 0L

    fun sample(packageName: String): Int? {
        if (shizuku.queryCurrentStatus() != ShizukuStatus.CONNECTED) return null
        val layer = findLayer(packageName) ?: return null
        val res = shizuku.execute("dumpsys SurfaceFlinger --latency '$layer'", CMD_TIMEOUT_MS)
        val fps = parseFps(res.stdout)
        // Sem dados: a camada pode ter mudado (jogo reaberto); procura de novo na próxima medição.
        if (fps == null) layerName = null
        return fps
    }

    private fun findLayer(pkg: String): String? {
        if (layerPackage == pkg) layerName?.let { return it }
        val res = shizuku.execute("dumpsys SurfaceFlinger --list", CMD_TIMEOUT_MS)
        val candidates = res.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.contains(pkg) && !it.startsWith("Bounds") && SAFE_LAYER.matches(it) }
            .toList()
        // O jogo desenha numa SurfaceView; a camada "(BLAST)" é a que recebe os quadros.
        val best = candidates.firstOrNull { it.startsWith("SurfaceView") && it.contains("BLAST") }
            ?: candidates.firstOrNull { it.startsWith("SurfaceView") }
            ?: candidates.firstOrNull()
        layerPackage = pkg
        layerName = best
        lastNewest = 0L
        return best
    }

    /** Saída do --latency: 1ª linha é o período do vsync; depois, 3 instantes (ns) por quadro. */
    private fun parseFps(out: String): Int? {
        val stamps = ArrayList<Long>()
        for (line in out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.drop(1)) {
            val parts = line.split(WHITESPACE)
            if (parts.size < 3) continue
            val actual = parts[1].toLongOrNull() ?: continue
            if (actual <= 0L || actual == Long.MAX_VALUE) continue // quadro ainda não apresentado
            stamps.add(actual)
        }
        val newest = stamps.maxOrNull() ?: return null
        // Nenhum quadro novo desde a última leitura: o jogo parou de desenhar.
        if (newest == lastNewest) return 0
        lastNewest = newest
        val count = stamps.count { it > newest - 1_000_000_000L }
        return count.takeIf { it >= 2 }
    }

    private companion object {
        const val CMD_TIMEOUT_MS = 4_000L
        val WHITESPACE = Regex("\\s+")
        // Só caracteres de nome de camada: o nome entra entre aspas simples no comando.
        val SAFE_LAYER = Regex("^[A-Za-z0-9_.\\[\\]()/#@:\$ -]+\$")
    }
}
