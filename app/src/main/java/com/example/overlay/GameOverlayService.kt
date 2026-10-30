package com.example.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.R
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Painel flutuante do jogo.
 *
 * - Fica recolhido como uma aba na lateral (esquerda ou direita) e pode ser arrastado;
 *   ao soltar, gruda na lateral mais próxima.
 * - 1º toque: a aba aumenta. 2º toque: abre o painel completo.
 * - Deslizar o painel para a lateral (ou tocar na seta) minimiza de volta.
 */
class GameOverlayService : Service() {

    private enum class State { COLLAPSED, PEEK, OPEN }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var tabRoot: FrameLayout? = null
    private var tabBody: FrameLayout? = null
    private var tabParams: WindowManager.LayoutParams? = null
    private var panelView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var state = State.COLLAPSED
    private var side = OverlayPrefs.SIDE_RIGHT
    private var snapAnimator: ValueAnimator? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val winW get() = dp(TAB_WINDOW_W)
    private val winH get() = dp(TAB_WINDOW_H)

    private val peekReset = Runnable { if (state == State.PEEK) setPeek(false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            OverlayPrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (tabRoot == null) addTab()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (tabRoot == null) return
        if (state == State.OPEN) closePanel(animated = false)
        snapAnimator?.cancel()
        positionTab(OverlayPrefs.side(this), OverlayPrefs.yFraction(this))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        panelView?.let { runCatching { wm.removeView(it) } }
        tabRoot?.let { runCatching { wm.removeView(it) } }
        panelView = null
        tabRoot = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Aba

    private fun addTab() {
        side = OverlayPrefs.side(this)

        val body = FrameLayout(this).apply {
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_overlay_bolt)
            }
            addView(icon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        tabBody = body

        val root = FrameLayout(this).apply {
            clipChildren = false
            addView(body, FrameLayout.LayoutParams(dp(TAB_BODY_W), dp(TAB_BODY_H)))
        }
        tabRoot = root

        val params = overlayParams(winW, winH)
        tabParams = params
        applySideStyle()
        root.setOnTouchListener(tabTouchListener)

        val (sw, sh) = screenSize()
        params.x = if (side == OverlayPrefs.SIDE_LEFT) 0 else sw - winW
        params.y = (OverlayPrefs.yFraction(this) * (sh - winH)).toInt().coerceIn(0, (sh - winH).coerceAtLeast(0))
        wm.addView(root, params)
    }

    private fun positionTab(newSide: Int, yFraction: Float) {
        val params = tabParams ?: return
        val root = tabRoot ?: return
        side = newSide
        val (sw, sh) = screenSize()
        params.x = if (side == OverlayPrefs.SIDE_LEFT) 0 else sw - winW
        params.y = (yFraction * (sh - winH)).toInt().coerceIn(0, (sh - winH).coerceAtLeast(0))
        applySideStyle()
        setPeekInstant(false)
        wm.updateViewLayout(root, params)
    }

    /** Ajusta forma, encaixe e pivô da aba conforme o lado em que está. */
    private fun applySideStyle() {
        val body = tabBody ?: return
        val r = dp(10).toFloat()
        val radii = if (side == OverlayPrefs.SIDE_LEFT) {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        } else {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        body.background = GradientDrawable().apply {
            setColor(COLOR_ORANGE)
            cornerRadii = radii
        }
        val lp = body.layoutParams as FrameLayout.LayoutParams
        lp.gravity = (if (side == OverlayPrefs.SIDE_LEFT) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        body.layoutParams = lp
        body.pivotX = if (side == OverlayPrefs.SIDE_LEFT) 0f else dp(TAB_BODY_W).toFloat()
        body.pivotY = dp(TAB_BODY_H) / 2f
    }

    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val tabTouchListener = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val params = tabParams ?: return false
            val root = tabRoot ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    handler.removeCallbacks(peekReset)
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        setPeekInstant(false)
                    }
                    if (dragging) {
                        val (sw, sh) = screenSize()
                        params.x = (startX + dx).toInt().coerceIn(0, (sw - winW).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, (sh - winH).coerceAtLeast(0))
                        wm.updateViewLayout(root, params)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) snapToSide() else onTabTap()
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToSide()
                }
            }
            return true
        }
    }

    private fun onTabTap() {
        when (state) {
            State.COLLAPSED -> setPeek(true)
            State.PEEK -> openPanel()
            State.OPEN -> Unit
        }
    }

    /** Aumenta (ou volta ao normal) a aba. */
    private fun setPeek(on: Boolean) {
        val body = tabBody ?: return
        handler.removeCallbacks(peekReset)
        state = if (on) State.PEEK else State.COLLAPSED
        val scale = if (on) PEEK_SCALE else 1f
        body.animate().cancel()
        body.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .start()
        if (on) handler.postDelayed(peekReset, PEEK_TIMEOUT_MS)
    }

    private fun setPeekInstant(on: Boolean) {
        val body = tabBody ?: return
        handler.removeCallbacks(peekReset)
        state = if (on) State.PEEK else State.COLLAPSED
        body.animate().cancel()
        val scale = if (on) PEEK_SCALE else 1f
        body.scaleX = scale
        body.scaleY = scale
    }

    /** Gruda a aba na lateral mais próxima, mantendo a altura. */
    private fun snapToSide() {
        val params = tabParams ?: return
        val root = tabRoot ?: return
        val (sw, sh) = screenSize()
        val centerX = params.x + winW / 2
        side = if (centerX < sw / 2) OverlayPrefs.SIDE_LEFT else OverlayPrefs.SIDE_RIGHT
        applySideStyle()

        val targetX = if (side == OverlayPrefs.SIDE_LEFT) 0 else sw - winW
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (tabRoot != null) wm.updateViewLayout(root, params)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val range = (sh - winH).coerceAtLeast(1)
                    OverlayPrefs.savePosition(this@GameOverlayService, side, params.y.toFloat() / range)
                }
            })
            start()
        }
    }

    // -------------------------------------------------------------- Painel

    private fun openPanel() {
        if (state == State.OPEN || panelView != null) return
        handler.removeCallbacks(peekReset)
        state = State.OPEN

        val (sw, sh) = screenSize()
        val panelW = minOf(dp(PANEL_W), sw / 2)
        val view = buildPanel()
        val params = overlayParams(panelW, sh).apply {
            x = if (side == OverlayPrefs.SIDE_LEFT) 0 else sw - panelW
            y = 0
        }
        panelView = view
        panelParams = params

        val hidden = if (side == OverlayPrefs.SIDE_LEFT) -panelW.toFloat() else panelW.toFloat()
        view.translationX = hidden
        wm.addView(view, params)
        tabRoot?.visibility = View.INVISIBLE
        view.animate()
            .translationX(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closePanel(animated: Boolean = true) {
        val view = panelView ?: return
        val width = view.width.takeIf { it > 0 } ?: dp(PANEL_W)

        fun finish() {
            runCatching { wm.removeView(view) }
            if (panelView === view) panelView = null
            panelParams = null
            tabRoot?.visibility = View.VISIBLE
            setPeekInstant(false)
        }

        if (!animated) {
            finish()
            return
        }
        val hidden = if (side == OverlayPrefs.SIDE_LEFT) -width.toFloat() else width.toFloat()
        view.animate()
            .translationX(hidden)
            .setDuration(160)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { finish() }
            .start()
    }

    private fun buildPanel(): LinearLayout {
        val r = dp(20).toFloat()
        val radii = if (side == OverlayPrefs.SIDE_LEFT) {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        } else {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }

        val title = TextView(this).apply {
            text = "FF GAME OPTIMIZER"
            setTextColor(COLOR_ORANGE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        }
        val subtitle = TextView(this).apply {
            text = "Painel do jogo"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
        }

        val minimize = TextView(this).apply {
            text = if (side == OverlayPrefs.SIDE_LEFT) "‹" else "›"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_SURFACE_VARIANT)
            }
            setOnClickListener { closePanel() }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(minimize, LinearLayout.LayoutParams(dp(36), dp(36)))
        }

        val divider = View(this).apply { setBackgroundColor(COLOR_BORDER) }

        val body = FrameLayout(this).apply {
            addView(
                TextView(context).apply {
                    text = "Em breve"
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(COLOR_SURFACE)
                cornerRadii = radii
                setStroke(dp(1), COLOR_BORDER)
            }
            addView(header)
            addView(
                divider,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                }
            )
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setOnTouchListener(panelSwipeListener)
        }
    }

    /** Deslizar o painel em direção à lateral em que ele está encostado minimiza. */
    private val panelSwipeListener = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                }

                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val toEdge = if (side == OverlayPrefs.SIDE_LEFT) -dx else dx
                    if (toEdge > dp(48) && abs(dx) > abs(dy)) closePanel()
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------- Utilidades

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w,
        h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Painel do jogo", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, GameOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_bolt)
            .setContentTitle("Painel do jogo ativo")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Fechar painel", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_STOP = "com.example.overlay.STOP"
        private const val CHANNEL_ID = "game_overlay"
        private const val NOTIFICATION_ID = 4101

        private const val TAB_BODY_W = 28
        private const val TAB_BODY_H = 60
        private const val PEEK_SCALE = 1.4f
        private const val TAB_WINDOW_W = 40
        private const val TAB_WINDOW_H = 84
        private const val PANEL_W = 260
        private const val PEEK_TIMEOUT_MS = 3000L

        private val COLOR_ORANGE = Color.parseColor("#FF5E00")
        private val COLOR_SURFACE = Color.parseColor("#12151D")
        private val COLOR_SURFACE_VARIANT = Color.parseColor("#181C26")
        private val COLOR_BORDER = Color.parseColor("#222734")
        private val COLOR_TEXT = Color.parseColor("#F4F6F9")
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#8E95A5")

        fun start(context: Context) {
            val intent = Intent(context, GameOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GameOverlayService::class.java))
        }
    }
}
