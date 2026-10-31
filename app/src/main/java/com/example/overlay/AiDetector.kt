package com.example.overlay

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Detector heurístico dos oponentes a partir do quadro da tela (sem modelo treinado).
 *
 * Duas pistas independentes:
 *  - marcador vermelho do jogo (nome/barra de vida): só vale se for uma barra fina e horizontal,
 *    o que descarta roupa/objeto vermelho;
 *  - cabeça + corpo: tom de pele compacto com cabelo escuro acima e corpo contrastando com o
 *    fundo, que é o que encontra os personagens sem skin (zerados) mesmo longe/agachados.
 *
 * O personagem do próprio jogador, em 3ª pessoa no centro-baixo, é descartado pela posição e pelo
 * tamanho da caixa. Só os alvos de fundo são marcados, no máximo [MAX_BOXES].
 */
internal object AiDetector {

    /** Lado da célula de amostragem, em pixels do quadro. */
    private const val CELL = 2

    /** Limite de jogadores marcados ao mesmo tempo. */
    private const val MAX_BOXES = 2

    /** Caixas que se sobrepõem são o mesmo alvo (marcador + cabeça do mesmo personagem). */
    private const val OVERLAP = 0.30f

    /** Tamanho máximo da caixa: acima disso é personagem da frente, não alvo de fundo. */
    private const val MAX_BODY_W = 0.12f
    private const val MAX_BODY_H = 0.46f

    /** Zona do próprio personagem (3ª pessoa): centro-baixo da tela. */
    private const val SELF_LEFT = 0.26f
    private const val SELF_RIGHT = 0.74f
    private const val SELF_BOTTOM = 0.56f

    // ---------------------------------------------------------------- marcador vermelho

    private const val MARK_MIN_PX = 5
    private const val MARK_MIN_ASPECT = 2.1f
    private const val MARK_MAX_W_FRAC = 0.055f
    private const val MARK_MAX_H_FRAC = 0.04f
    private const val MARK_MIN_FILL = 0.28f
    private const val MARK_MIN_CELLS = 2

    /** Corpo medido a partir da largura da barra (proporção humana). */
    private const val BODY_W = 1.5f
    private const val BODY_H = 3.6f

    // ---------------------------------------------------------------- cabeça/corpo

    private const val HEAD_MIN_PX = 3
    private const val HEAD_MAX_PX = 26
    private const val HEAD_MAX_FRAC = 0.02f
    private const val HEAD_MIN_ASPECT = 0.45f
    private const val HEAD_MAX_ASPECT = 2.0f
    private const val HEAD_MIN_FILL = 0.30f
    private const val HAIR_MIN = 0.18f
    private const val HEAD_BODY_W = 1.6f
    private const val HEAD_BODY_H = 3.0f

    fun detect(bitmap: Bitmap): List<Hitbox> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 64 || h < 64) return emptyList()

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val found = ArrayList<Candidate>(8)
        findMarkers(pixels, w, h, found)
        findHeads(pixels, w, h, found)
        return pick(found)
    }

    private class Candidate(val box: Hitbox, val score: Float)

    // ---------------------------------------------------------------- marcador

    private fun findMarkers(pixels: IntArray, w: Int, h: Int, out: MutableList<Candidate>) {
        val gw = w / CELL
        val gh = h / CELL
        if (gw < 4 || gh < 4) return

        val mask = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val y0 = gy * CELL
            val y1 = minOf(y0 + CELL, h)
            for (gx in 0 until gw) {
                val x0 = gx * CELL
                val x1 = minOf(x0 + CELL, w)
                var hit = false
                yLoop@ for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        if (isEnemyMark(pixels[row + x])) {
                            hit = true
                            break@yLoop
                        }
                    }
                }
                mask[gy * gw + gx] = hit
            }
        }

        for (comp in components(mask, gw, gh)) {
            if (comp[4] < MARK_MIN_CELLS) continue
            val fine = refine(
                pixels, w, h,
                (comp[0] * CELL - CELL).coerceAtLeast(0),
                (comp[1] * CELL - CELL).coerceAtLeast(0),
                ((comp[2] + 1) * CELL).coerceAtMost(w),
                ((comp[3] + 1) * CELL).coerceAtMost(h)
            ) { isEnemyMark(it) } ?: continue

            val pw = fine[2] - fine[0] + 1
            val ph = fine[3] - fine[1] + 1
            if (pw < MARK_MIN_PX) continue
            if (pw > w * MARK_MAX_W_FRAC) continue
            if (ph > h * MARK_MAX_H_FRAC) continue
            if (pw < ph * MARK_MIN_ASPECT) continue
            val fill = fine[4].toFloat() / (pw * ph)
            if (fill < MARK_MIN_FILL) continue

            val box = bodyFromMark(fine, w, h) ?: continue
            if (!valid(box)) continue
            val score = 10f + (pw.toFloat() / ph).coerceAtMost(6f) + fill * 3f
            out.add(Candidate(box, score))
        }
    }

    /** Barra do jogo: vermelho vivo e estreito. Vermelho de roupa/objeto não passa na forma. */
    private fun isEnemyMark(color: Int): Boolean {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        if (r < 120 || g > 66 || b > 66) return false
        return r >= g + 70 && r >= b + 70
    }

    private fun bodyFromMark(fine: IntArray, w: Int, h: Int): Hitbox? {
        val pw = (fine[2] - fine[0] + 1).toFloat()
        val left = fine[0].toFloat() / w
        val right = (fine[2] + 1).toFloat() / w
        val bottom = (fine[3] + 1).toFloat() / h
        val cx = (left + right) / 2f
        val bw = (pw * BODY_W / w).coerceAtLeast(0.012f)
        val bh = (pw * BODY_H / h).coerceAtLeast(0.02f)
        return clamp(cx - bw / 2f, bottom, cx + bw / 2f, bottom + bh)
    }

    // ---------------------------------------------------------------- cabeça + corpo

    private fun findHeads(pixels: IntArray, w: Int, h: Int, out: MutableList<Candidate>) {
        val gw = w / CELL
        val gh = h / CELL
        if (gw < 4 || gh < 4) return

        val mask = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val y0 = gy * CELL
            val y1 = minOf(y0 + CELL, h)
            for (gx in 0 until gw) {
                val x0 = gx * CELL
                val x1 = minOf(x0 + CELL, w)
                var hit = false
                yLoop@ for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        if (isSkin(pixels[row + x])) {
                            hit = true
                            break@yLoop
                        }
                    }
                }
                mask[gy * gw + gx] = hit
            }
        }

        for (comp in components(mask, gw, gh)) {
            if (comp[4] < 2) continue
            val fine = refine(
                pixels, w, h,
                (comp[0] * CELL - CELL).coerceAtLeast(0),
                (comp[1] * CELL - CELL).coerceAtLeast(0),
                ((comp[2] + 1) * CELL).coerceAtMost(w),
                ((comp[3] + 1) * CELL).coerceAtMost(h)
            ) { isSkin(it) } ?: continue

            val pw = fine[2] - fine[0] + 1
            val ph = fine[3] - fine[1] + 1
            if (pw < HEAD_MIN_PX || ph < HEAD_MIN_PX) continue
            if (pw > HEAD_MAX_PX || ph > HEAD_MAX_PX) continue
            if (pw > w * HEAD_MAX_FRAC) continue
            val aspect = pw.toFloat() / ph
            if (aspect < HEAD_MIN_ASPECT || aspect > HEAD_MAX_ASPECT) continue
            val fill = fine[4].toFloat() / (pw * ph)
            if (fill < HEAD_MIN_FILL) continue

            val cx = ((fine[0] + fine[2]) / 2f) / w
            val cy = ((fine[1] + fine[3]) / 2f) / h
            if (cx < 0.06f || cx > 0.94f || cy < 0.10f || cy > 0.80f) continue

            val hair = hairRatio(pixels, w, h, fine)
            if (hair < HAIR_MIN) continue
            if (!bodyContrast(pixels, w, h, fine)) continue

            val box = bodyFromHead(fine, w, h) ?: continue
            if (!valid(box)) continue
            out.add(Candidate(box, 5f + hair * 4f))
        }
    }

    /** Tom de pele humano: quente, com verde acima do azul, sem estourar em vermelho puro. */
    private fun isSkin(color: Int): Boolean {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        if (r < 70 || r > 248) return false
        if (g < 40 || g > 210) return false
        if (b < 28 || b > 190) return false
        if (r - g < 10 || g - b < 3) return false
        if (r - g > 95 || g - b > 85) return false
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return max - min in 18..170
    }

    /** Cabelo/elmo escuro logo acima da cabeça. */
    private fun hairRatio(pixels: IntArray, w: Int, h: Int, fine: IntArray): Float {
        val hh = fine[3] - fine[1] + 1
        val band = (hh / 3).coerceIn(2, 5)
        val y1 = fine[1]
        val y0 = (y1 - band).coerceAtLeast(0)
        if (y0 >= y1) return 0f
        var dark = 0
        var total = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in fine[0]..fine[2]) {
                val c = pixels[row + x]
                val luma = luma(c)
                total++
                if (luma < 95) dark++
            }
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    /** O corpo abaixo da cabeça precisa destoar do fundo (roupa clara ou escura). */
    private fun bodyContrast(pixels: IntArray, w: Int, h: Int, fine: IntArray): Boolean {
        val hw = fine[2] - fine[0] + 1
        val cx = (fine[0] + fine[2]) / 2
        val by0 = (fine[3] + 1).coerceAtMost(h - 1)
        val by1 = (by0 + hw * 3).coerceAtMost(h - 1)
        if (by1 - by0 < 3) return false
        val bodyW = (hw * 1.6f).toInt().coerceAtLeast(3)
        val bx0 = (cx - bodyW / 2).coerceAtLeast(0)
        val bx1 = (cx + bodyW / 2).coerceAtMost(w - 1)

        val inside = avgLuma(pixels, w, bx0, by0, bx1, by1)
        val left = avgLuma(pixels, w, (bx0 - hw).coerceAtLeast(0), by0, bx0 - 1, by1)
        val right = avgLuma(pixels, w, bx1 + 1, by0, (bx1 + hw).coerceAtMost(w - 1), by1)
        val bg = when {
            left >= 0f && right >= 0f -> (left + right) / 2f
            left >= 0f -> left
            right >= 0f -> right
            else -> return true
        }
        return abs(inside - bg) >= 12f
    }

    private fun avgLuma(pixels: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        if (x1 < x0 || y1 < y0) return -1f
        var sum = 0L
        var n = 0
        var y = y0
        while (y <= y1) {
            val row = y * w
            var x = x0
            while (x <= x1) {
                sum += luma(pixels[row + x])
                n++
                x += 2
            }
            y += 2
        }
        return if (n == 0) -1f else sum.toFloat() / n
    }

    private fun luma(color: Int): Int {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    private fun bodyFromHead(fine: IntArray, w: Int, h: Int): Hitbox? {
        val hw = (fine[2] - fine[0] + 1).toFloat()
        val hh = (fine[3] - fine[1] + 1).toFloat()
        val cx = (fine[0] + fine[2]) / 2f
        val top = (fine[3] + 1).toFloat()
        val bw = hw * HEAD_BODY_W
        val bh = hh * HEAD_BODY_H
        return clamp(
            (cx - bw / 2f) / w,
            top / h,
            (cx + bw / 2f) / w,
            (top + bh) / h
        )
    }

    // ---------------------------------------------------------------- seleção

    private fun valid(box: Hitbox): Boolean {
        val bw = box.right - box.left
        val bh = box.bottom - box.top
        if (bw <= 0f || bh <= 0f) return false
        if (bw > MAX_BODY_W || bh > MAX_BODY_H) return false
        if (box.top < 0.05f || box.bottom > 0.90f) return false
        if (box.left < 0.04f || box.right > 0.96f) return false
        val cx = (box.left + box.right) / 2f
        return !(cx in SELF_LEFT..SELF_RIGHT && box.bottom > SELF_BOTTOM)
    }

    private fun pick(all: List<Candidate>): List<Hitbox> {
        if (all.isEmpty()) return emptyList()
        val chosen = ArrayList<Hitbox>(MAX_BOXES)
        for (c in all.sortedByDescending { it.score }) {
            if (chosen.size >= MAX_BOXES) break
            if (chosen.any { overlaps(it, c.box) }) continue
            chosen.add(c.box)
        }
        return chosen
    }

    private fun overlaps(a: Hitbox, b: Hitbox): Boolean {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0f || iy <= 0f) return false
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val smaller = minOf(areaA, areaB)
        return smaller > 0f && ix * iy >= smaller * OVERLAP
    }

    private fun clamp(left: Float, top: Float, right: Float, bottom: Float): Hitbox? {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        if (r <= l || b <= t) return null
        return Hitbox(l, t, r, b)
    }

    // ---------------------------------------------------------------- componentes

    /** Menor retângulo real de pixels que passam no teste dentro da área do componente. */
    private inline fun refine(
        pixels: IntArray,
        w: Int,
        h: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        test: (Int) -> Boolean
    ): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        var count = 0
        for (y in top until bottom) {
            val row = y * w
            for (x in left until right) {
                if (!test(pixels[row + x])) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                count++
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX, maxY, count)
    }

    /** Componentes conexos (8 vizinhos); devolve [minX, minY, maxX, maxY, células]. */
    private fun components(mask: BooleanArray, gw: Int, gh: Int): List<IntArray> {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val out = ArrayList<IntArray>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = start % gw
            var maxX = minX
            var minY = start / gw
            var maxY = minY
            var count = 0
            while (sp > 0) {
                val c = stack[--sp]
                count++
                val cx = c % gw
                val cy = c / gw
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny < 0 || ny >= gh) continue
                    for (dx in -1..1) {
                        val nx = cx + dx
                        if (nx < 0 || nx >= gw) continue
                        val ni = ny * gw + nx
                        if (!mask[ni] || seen[ni]) continue
                        seen[ni] = true
                        stack[sp++] = ni
                    }
                }
            }
            out.add(intArrayOf(minX, minY, maxX, maxY, count))
        }
        return out
    }
}
