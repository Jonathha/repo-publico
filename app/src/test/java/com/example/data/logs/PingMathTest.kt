package com.example.data.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingMathTest {

    @Test
    fun statsBasicas() {
        val s = PingMath.stats(listOf(10, 20, null, 30))
        assertEquals(4, s.total)
        assertEquals(1, s.failed)
        assertEquals(10, s.minMs)
        assertEquals(30, s.maxMs)
        assertEquals(20.0, s.avgMs!!, 0.001)
        assertEquals(25.0, s.failureRate!!, 0.001)
    }

    @Test
    fun jitterIgnoraParesComFalha() {
        // pares válidos: (10,20)=10 e (20,40)=20 -> 15; o par com null não conta
        val s = PingMath.stats(listOf(10, 20, 40, null, 50))
        assertEquals(15.0, s.jitterMs!!, 0.001)
    }

    @Test
    fun jitterNuloSemDadosSuficientes() {
        assertNull(PingMath.stats(listOf(10, 20)).jitterMs)
        assertNull(PingMath.stats(listOf(10, null, 20)).jitterMs)
    }

    @Test
    fun semMedicoes() {
        val s = PingMath.stats(emptyList())
        assertEquals(0, s.total)
        assertNull(s.avgMs)
        assertNull(s.failureRate)
    }

    @Test
    fun todasFalharam() {
        val s = PingMath.stats(listOf(null, null))
        assertNull(s.minMs)
        assertNull(s.avgMs)
        assertEquals(100.0, s.failureRate!!, 0.001)
    }

    @Test
    fun baldesAgrupamEMantemVazios() {
        val now = 1_000L
        val b = PingMath.buckets(
            points = listOf(0L to 10, 10L to 30, 600L to null, 999L to 50),
            now = now, windowMs = 1_000L, bucketMs = 500L
        )
        assertEquals(2, b.size)
        assertEquals(20.0, b[0].avgMs!!, 0.001)
        assertEquals(2, b[0].count)
        assertEquals(1, b[1].failed)
        assertEquals(50.0, b[1].avgMs!!, 0.001)
    }
}
