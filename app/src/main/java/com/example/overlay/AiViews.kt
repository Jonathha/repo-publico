package com.example.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Cor das caixas da A.I: ciano, fora do vermelho usado na detecção (sem realimentar a captura). */
internal object AiColors {
    val BOX = Color.parseColor("#22D3EE")
    val BOX_FILL = Color.parseColor("#3322D3EE")
}

/**
 * Janela transparente que desenha as caixas sobre os oponentes. As caixas perseguidas a cada quadro
 * (interpolação) acompanham o movimento sem saltar, e somem suavemente quando o alvo desaparece.
 */
internal class HitboxOverlayView(context: Context) : View(context) {

    private class Live(var left: Float, var top: Float, var right: Float, var bottom: Float) {
        var alpha = 0f
        var leaving = false
    }

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        color = AiColors.BOX
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AiColors.BOX_FILL
    }
    private val rect = RectF()
    private val live = ArrayList<Live>()
    private var targets: List<Hitbox> = emptyList()
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val moving = step()
            invalidate()
            // Para quando nada mais se move; um novo conjunto de caixas religa o laço.
            if (moving) postOnAnimation(this) else running = false
        }
    }

    fun setTargets(boxes: List<Hitbox>) {
        targets = boxes
        if (!running) {
            running = true
            postOnAnimation(tick)
        }
    }

    /** Um quadro da animação; devolve true enquanto ainda há movimento. */
    private fun step(): Boolean {
        var moving = false
        val used = MutableList(live.size) { false }
        for (target in targets) {
            val index = nearest(target, used)
            if (index < 0) {
                live.add(Live(target.left, target.top, target.right, target.bottom).apply { leaving = false })
                used.add(true)
                moving = true
            } else {
                used[index] = true
                val box = live[index]
                box.leaving = false
                if (approach(box, target)) moving = true
                if (abs(box.alpha - 1f) > 0.02f) {
                    box.alpha += (1f - box.alpha) * EASE
                    moving = true
                } else {
                    box.alpha = 1f
                }
            }
        }
        for (i in live.indices) {
            if (used.getOrElse(i) { false }) continue
            val box = live[i]
            box.leaving = true
            box.alpha -= box.alpha * EASE
            if (box.alpha <= 0.03f) {
                live.removeAt(i)
                return true
            }
            moving = true
        }
        if (live.isEmpty()) moving = false
        return moving
    }

    private fun nearest(target: Hitbox, used: List<Boolean>): Int {
        val cx = (target.left + target.right) / 2f
        val cy = (target.top + target.bottom) / 2f
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in live.indices) {
            if (used.getOrElse(i) { false } || live[i].leaving) continue
            val box = live[i]
            val dx = (box.left + box.right) / 2f - cx
            val dy = (box.top + box.bottom) / 2f - cy
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private fun approach(box: Live, target: Hitbox): Boolean {
        val values = floatArrayOf(box.left, box.top, box.right, box.bottom)
        val goals = floatArrayOf(target.left, target.top, target.right, target.bottom)
        var moving = false
        for (i in values.indices) {
            val diff = goals[i] - values[i]
            if (abs(diff) > SNAP) {
                values[i] += diff * EASE
                moving = true
            } else {
                values[i] = goals[i]
            }
        }
        box.left = values[0]
        box.top = values[1]
        box.right = values[2]
        box.bottom = values[3]
        return moving
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = 6f * density
        for (box in live) {
            val alpha = box.alpha.coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue
            rect.set(box.left * w, box.top * h, box.right * w, box.bottom * h)
            if (rect.width() < 2f || rect.height() < 2f) continue
            fill.alpha = (56 * alpha).toInt()
            stroke.alpha = (235 * alpha).toInt()
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        live.clear()
        targets = emptyList()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val EASE = 0.32f
        const val SNAP = 0.0008f
    }
}

/** Linha única da A.I: nome, descrição do estado e o interruptor de captura. */
internal class AiToggleRowView(
    context: Context,
    private val onToggle: () -> Unit
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val description: TextView
    private val switch = BoostSwitchView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setPadding(0, dp(7), 0, dp(7))
        setOnClickListener { onToggle() }
        background = RippleDrawable(
            ColorStateList.valueOf(OverlayColors.ORANGE_SOFT),
            null,
            GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
            }
        )

        val title = text("Detectar oponentes", 12f, OverlayColors.TEXT_DARK, bold = true)
        description = text("Caixas sobre os oponentes vistos na tela", 10f, OverlayColors.TEXT_MUTED).apply {
            maxLines = 3
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title)
            addView(
                description,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) }
            )
        }
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(switch, LayoutParams(dp(38), dp(22)).apply { marginStart = dp(8) })
    }

    fun render(on: Boolean, busy: Boolean, detail: String?) {
        switch.setChecked(on, animate = true)
        switch.alpha = if (busy) 0.55f else 1f
        description.text = detail ?: "Caixas sobre os oponentes vistos na tela"
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
}
