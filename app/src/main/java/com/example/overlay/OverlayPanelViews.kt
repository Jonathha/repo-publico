package com.example.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** Cores do painel do jogo (painel claro com detalhes laranja, como na referência). */
internal object OverlayColors {
    val ORANGE = Color.parseColor("#FF5E00")
    val ORANGE_DARK = Color.parseColor("#D44A00")
    val ORANGE_SOFT = Color.parseColor("#26FF5E00")
    val PANEL_FILL = Color.parseColor("#F5F6F8")
    val TEXT_DARK = Color.parseColor("#262A35")
    val TEXT_MUTED = Color.parseColor("#7C8294")
    val TRACK = Color.parseColor("#D5D8DF")
}

/**
 * Metade do painel em forma de seta apontando para o centro: reta na lateral da tela e com a
 * face interna inclinada, destacada por uma borda laranja. Só a área da forma recebe toques;
 * o resto do retângulo deixa o toque passar (para fechar pelo centro vazio).
 */
internal class SlantedPanel(
    context: Context,
    private val onLeft: Boolean
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayColors.PANEL_FILL
        style = Paint.Style.FILL
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayColors.ORANGE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val shape = Path()
    private val diagonal = Path()
    private val region = Region()

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val half = edge.strokeWidth / 2f
        val tip = w - half              // vértice da seta, mais perto do centro da tela
        val base = tip * TIP_FRACTION   // onde a face inclinada encontra o topo e a base
        val hh = h.toFloat()
        fun x(v: Float) = if (onLeft) v else w - v

        shape.reset()
        shape.moveTo(x(0f), 0f)
        shape.lineTo(x(base), 0f)
        shape.lineTo(x(tip), hh / 2f)
        shape.lineTo(x(base), hh)
        shape.lineTo(x(0f), hh)
        shape.close()

        diagonal.reset()
        diagonal.moveTo(x(base), 0f)
        diagonal.lineTo(x(tip), hh / 2f)
        diagonal.lineTo(x(base), hh)

        region.setPath(shape, Region(0, 0, w, h))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(shape, fill)
        canvas.drawPath(diagonal, edge)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            !region.contains(event.x.toInt(), event.y.toInt())
        ) return false
        return super.onTouchEvent(event)
    }

    companion object {
        /** Largura do topo/base da forma em relação ao ponto mais largo. */
        const val TIP_FRACTION = 0.66f
    }
}

/**
 * Barra de nível vertical (preenche de baixo para cima), arrastável. [onLevel] recebe 0..1 e
 * [onTouching] avisa quando o dedo entra/sai. Desativada (isEnabled = false) ignora toques.
 */
internal class VerticalLevelView(
    context: Context,
    initialLevel: Float,
    private val onTouching: (Boolean) -> Unit = {},
    private val onLevel: (Float) -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayColors.TRACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayColors.ORANGE }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayColors.ORANGE_DARK }
    private val clip = Path()
    private val bounds = RectF()

    private var level = initialLevel.coerceIn(0f, 1f)

    var isTouching = false
        private set

    /** Atualiza o nível por fora (ex.: botões de volume), sem disparar [onLevel]. */
    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 8f * density
        bounds.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w, h, track)
        val top = h - h * level
        canvas.drawRect(0f, top, w, h, fill)
        val t = 2f * density
        canvas.drawRect(0f, (top - t).coerceAtLeast(0f), w, (top + t).coerceAtMost(h), thumb)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isTouching = true
                onTouching(true)
                update(event.y)
            }
            MotionEvent.ACTION_MOVE -> update(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isTouching = false
                onTouching(false)
            }
        }
        return true
    }

    private fun update(y: Float) {
        if (height == 0) return
        level = 1f - (y / height).coerceIn(0f, 1f)
        invalidate()
        onLevel(level)
    }
}
