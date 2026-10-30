package com.example.data.logs

data class PingStats(
    val total: Int,
    val failed: Int,
    val minMs: Int?,
    val maxMs: Int?,
    val avgMs: Double?,
    /** Média da variação absoluta entre medições bem-sucedidas consecutivas; nulo sem dados suficientes. */
    val jitterMs: Double?
) {
    /** % de tentativas sem resposta (inclui aparelho sem rede). Nulo sem medições. */
    val failureRate: Double? get() = if (total > 0) failed * 100.0 / total else null
}

data class PingBucket(val startMs: Long, val avgMs: Double?, val failed: Int, val count: Int)

object PingMath {

    private const val MIN_JITTER_PAIRS = 2

    /** [rtts] em ordem cronológica; nulo = tentativa sem resposta. */
    fun stats(rtts: List<Int?>): PingStats {
        val ok = rtts.filterNotNull()
        var diffSum = 0L
        var pairs = 0
        for (i in 1 until rtts.size) {
            val a = rtts[i - 1]
            val b = rtts[i]
            if (a != null && b != null) {
                diffSum += kotlin.math.abs(b - a)
                pairs++
            }
        }
        return PingStats(
            total = rtts.size,
            failed = rtts.size - ok.size,
            minMs = ok.minOrNull(),
            maxMs = ok.maxOrNull(),
            avgMs = if (ok.isEmpty()) null else ok.sum().toDouble() / ok.size,
            jitterMs = if (pairs >= MIN_JITTER_PAIRS) diffSum.toDouble() / pairs else null
        )
    }

    /** Divide [windowMs] até [now] em baldes fixos de [bucketMs]; baldes sem medição ficam com count = 0. */
    fun buckets(points: List<Pair<Long, Int?>>, now: Long, windowMs: Long, bucketMs: Long): List<PingBucket> {
        val n = (windowMs / bucketMs).toInt()
        val start = now - windowMs
        val sums = LongArray(n)
        val oks = IntArray(n)
        val fails = IntArray(n)
        for ((ts, rtt) in points) {
            if (ts < start || ts > now) continue
            val i = ((ts - start) / bucketMs).toInt().coerceAtMost(n - 1)
            if (rtt == null) fails[i]++ else {
                sums[i] += rtt.toLong()
                oks[i]++
            }
        }
        return List(n) { i ->
            PingBucket(
                startMs = start + i * bucketMs,
                avgMs = if (oks[i] > 0) sums[i].toDouble() / oks[i] else null,
                failed = fails[i],
                count = oks[i] + fails[i]
            )
        }
    }
}
