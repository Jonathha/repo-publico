package com.example.data.logs

import com.example.data.database.PingDao
import com.example.data.database.PingSampleEntity
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Mede o tempo de handshake TCP até [HOST]:[PORT] (IP literal: sem DNS na medição).
 * É a latência do aparelho até esse servidor, NÃO a latência interna de uma partida de jogo.
 * Só roda com o app em primeiro plano; o histórico cobre apenas esses períodos.
 */
class PingSampler(private val dao: PingDao) {

    private var lastSampleAt = 0L

    /** Deve rodar dentro de repeatOnLifecycle(STARTED); cancelar a coroutine encerra a coleta. */
    suspend fun run() {
        prune()
        var sinceLastPrune = 0
        while (true) {
            val wait = INTERVAL_MS - (System.currentTimeMillis() - lastSampleAt)
            if (wait > 0) delay(wait) // evita medição duplicada quando a tela é recriada
            val rtt = withContext(Dispatchers.IO) { measure() }
            lastSampleAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                dao.insert(PingSampleEntity(timestamp = lastSampleAt, target = "$HOST:$PORT", rttMs = rtt))
            }
            if (++sinceLastPrune >= PRUNE_EVERY) {
                prune()
                sinceLastPrune = 0
            }
        }
    }

    private suspend fun prune() = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
    }

    /** RTT em ms, ou nulo se a conexão falhou/expirou. */
    private fun measure(): Int? = try {
        Socket().use { socket ->
            val t0 = System.nanoTime()
            socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            ((System.nanoTime() - t0 + 500_000) / 1_000_000).toInt().coerceAtLeast(1)
        }
    } catch (_: IOException) {
        null
    }

    companion object {
        const val HOST = "1.1.1.1"
        const val PORT = 443
        const val RETENTION_MS = 4 * 24 * 60 * 60 * 1000L
        private const val TIMEOUT_MS = 3_000
        private const val INTERVAL_MS = 30_000L
        private const val PRUNE_EVERY = 120
    }
}
