package com.example.overlay

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** Cores da área Boost: claro, com verde-petróleo no lugar do laranja do restante do painel. */
internal object BoostColors {
    val ACCENT = Color.parseColor("#0F8B8D")
    val ACCENT_SOFT = Color.parseColor("#240F8B8D")
    val ERROR = Color.parseColor("#B3261E")
    val DIVIDER = Color.parseColor("#E3E5EA")
}

/** Interruptor com bolinha: a bolinha desliza e a trilha troca de cor suavemente. */
internal class BoostSwitchView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private var progress = 0f
    private var animator: ValueAnimator? = null

    fun setChecked(on: Boolean, animate: Boolean) {
        val target = if (on) 1f else 0f
        animator?.cancel()
        animator = null
        if (!animate || !isAttachedToWindow || width == 0 || progress == target) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 170
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        track.color = ArgbEvaluator().evaluate(progress, OverlayColors.TRACK, BoostColors.ACCENT) as Int
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, track)
        val pad = 3f * density
        val d = h - 2f * pad
        val cx = pad + d / 2f + (w - 2f * pad - d) * progress
        canvas.drawCircle(cx, h / 2f, d / 2f, thumb)
    }
}

/** Linha de uma otimização: nome, descrição curta e o interruptor. */
internal class BoostRowView(
    context: Context,
    val option: BoostOption,
    onClick: () -> Unit
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
        setOnClickListener { onClick() }
        background = RippleDrawable(
            ColorStateList.valueOf(BoostColors.ACCENT_SOFT),
            null,
            GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
            }
        )

        val title = text(option.title, 12f, OverlayColors.TEXT_DARK, bold = true)
        description = text(option.description, 10f, OverlayColors.TEXT_MUTED, bold = false).apply { maxLines = 3 }
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

    fun render(state: BoostState, reason: String?) {
        val supported = reason == null
        switch.setChecked(state == BoostState.ON, animate = true)
        switch.alpha = if (state == BoostState.BUSY) 0.55f else 1f
        description.text = reason ?: option.description
        alpha = if (supported) 1f else 0.5f
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
}
