package com.example.overlay

import android.graphics.Bitmap

/**
 * Detector heurístico dos oponentes 3D a partir do quadro da tela.
 *
 * Não há modelo treinado: a marcação vermelha que o jogo desenha sobre o inimigo (barra de vida e
 * nome) ancora o personagem. O marcador é refinado pixel a pixel (sem a quantização da grade) e a
 * caixa do corpo é medida a partir dele, com proporção humana. A zona do próprio personagem, no
 * centro-baixo da tela (3ª pessoa), é descartada.
 *
 * Os limites ficam nas constantes abaixo para calibrar.
 */
internal object AiDetector {

    /** Lado da célula de amostragem, em pixels do quadro reduzido. */
    private const val CELL = 4
    private const val MIN_CELLS = 3
    private const val MAX_BOXES = 8
    private const val MAX_FRACTION = 0.28f
    private const val MERGE_GAP = 0.012f

    /** Proporções do corpo medidas a partir da largura do marcador. */
    private const val BODY_WIDTH = 1.7f
    private const val BODY_HEIGHT = 3.4f
    private const val HEAD_GAP = 0.15f
    private const val MIN_BODY = 0.025f

    /**
     * Zona do próprio personagem em 3ª pessoa: nada é marcado no centro-baixo, onde o personagem
     * do jogador (e a arma) aparece na frente da câmera.
     */
    private const val SELF_LEFT = 0.34f
    private const val SELF_RIGHT = 0.66f
    private const val SELF_TOP = 0.60f

    fun detect(bitmap: Bitmap): List<Hitbox> {
        val w = bitmap.width
        val h = bitmap.height
        val gw = w / CELL
        val gh = h / CELL
        if (gw < 4 || gh < 4) return emptyList()

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val grid = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val row = (gy * CELL + CELL / 2) * w
            for (gx in 0 until gw) {
                grid[gy * gw + gx] = isEnemyMark(pixels[row + gx * CELL + CELL / 2])
            }
        }

        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val clusters = ArrayList<IntArray>()

        for (start in grid.indices) {
            if (!grid[start] || seen[start]) continue
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
                        if (!grid[ni] || seen[ni]) continue
                        seen[ni] = true
                        stack[sp++] = ni
                    }
                }
            }
            if (count >= MIN_CELLS) clusters.add(intArrayOf(minX, minY, maxX, maxY))
        }

        val raw = clusters.mapNotNull { box ->
            // Refina nos pixels originais: a grade de amostragem só localiza, a medida é exata.
            val fine = refine(
                pixels, w, h,
                (box[0] * CELL - CELL).coerceAtLeast(0),
                (box[1] * CELL - CELL).coerceAtLeast(0),
                ((box[2] + 1) * CELL + CELL).coerceAtMost(w),
                ((box[3] + 1) * CELL + CELL).coerceAtMost(h)
            ) ?: return@mapNotNull null

            val left = fine[0].toFloat() / w
            val top = fine[1].toFloat() / h
            val right = (fine[2] + 1).toFloat() / w
            val bottom = (fine[3] + 1).toFloat() / h
            val bw = right - left
            val bh = bottom - top
            if (bw <= 0f || bh <= 0f || bw > MAX_FRACTION || bh > MAX_FRACTION) return@mapNotNull null

            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            if (cx in SELF_LEFT..SELF_RIGHT && cy >= SELF_TOP) return@mapNotNull null

            body(left, top, right, bottom)
        }

        return merge(raw).take(MAX_BOXES)
    }

    /** Menor retângulo real de pixels marcados dentro da área do componente; null se não houver. */
    private fun refine(
        pixels: IntArray,
        w: Int,
        h: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in top until bottom) {
            val row = y * w
            for (x in left until right) {
                if (!isEnemyMark(pixels[row + x])) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX, maxY)
    }

    /** Marca vermelha do jogo: vermelho saturado e escuro o bastante para não pegar o laranja do painel. */
    private fun isEnemyMark(color: Int): Boolean {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        if (r < 110 || g > 72 || b > 72) return false
        return r >= g + 60 && r >= b + 60
    }

    /** Do marcador (nome/vida acima do personagem) para a caixa medida do corpo 3D. */
    private fun body(left: Float, top: Float, right: Float, bottom: Float): Hitbox {
        val cx = (left + right) / 2f
        val bw = ((right - left) * BODY_WIDTH).coerceAtLeast(MIN_BODY)
        val bh = ((right - left) * BODY_HEIGHT).coerceAtLeast(MIN_BODY)
        val newTop = bottom - (bottom - top) * HEAD_GAP
        return Hitbox(
            left = (cx - bw / 2f).coerceIn(0f, 1f),
            top = newTop.coerceIn(0f, 1f),
            right = (cx + bw / 2f).coerceIn(0f, 1f),
            bottom = (newTop + bh).coerceIn(0f, 1f)
        )
    }

    /** Junta caixas vizinhas: barras quebradas por números viram uma só. */
    private fun merge(boxes: List<Hitbox>): List<Hitbox> {
        val list = boxes.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val gapX = maxOf(a.left, b.left) - minOf(a.right, b.right)
                    val gapY = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
                    if (gapX <= MERGE_GAP && gapY <= MERGE_GAP) {
                        list[i] = Hitbox(
                            minOf(a.left, b.left),
                            minOf(a.top, b.top),
                            maxOf(a.right, b.right),
                            maxOf(a.bottom, b.bottom)
                        )
                        list.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
        return list
    }
}
