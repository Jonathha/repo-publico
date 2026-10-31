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
import android.content.res.ColorStateList
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.freefire.FreeFireDetector
import com.example.data.logs.ActiveAppsCollector
import com.example.data.logs.PingSampler
import com.example.data.shizuku.ShizukuManager
import com.example.data.system.GameFpsMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Painel flutuante do jogo.
 *
 * - Só aparece (e só abre) com o Free Fire em primeiro plano.
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
    private var panelView: FrameLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var state = State.COLLAPSED
    private var side = OverlayPrefs.SIDE_RIGHT
    private var snapAnimator: ValueAnimator? = null

    // Estado do jogo: a aba e o painel só existem para o usuário enquanto o jogo está em primeiro plano.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitorJob: Job? = null
    private val shizuku by lazy { ShizukuManager(applicationContext) }
    private var collector: ActiveAppsCollector? = null
    private var gameActive = false
    private var gamePackage: String? = null
    private var lastStatusText: String? = null

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
        startGameMonitor()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (tabRoot == null) return
        if (state == State.OPEN) closePanel(animated = false)
        snapAnimator?.cancel()
        repositionTab()
        // A rotação (ex.: o jogo forçando paisagem) assenta depois do callback: confere de novo.
        handler.removeCallbacks(settleReposition)
        handler.postDelayed(settleReposition, SETTLE_MS)
        handler.postDelayed(settleReposition, SETTLE_MS * 3)
    }

    private val settleReposition = Runnable { repositionTab() }

    /** Recoloca a aba dentro da área visível atual, na lateral e altura salvas. */
    private fun repositionTab() {
        if (tabRoot == null || panelView != null || tabTouchListener.isDragging) return
        positionTab(OverlayPrefs.side(this), OverlayPrefs.yFraction(this))
    }

    override fun onDestroy() {
        scope.cancel()
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
            visibility = View.GONE // só aparece quando o jogo estiver em primeiro plano
            addView(body, FrameLayout.LayoutParams(dp(TAB_BODY_W), dp(TAB_BODY_H)))
        }
        tabRoot = root

        val params = overlayParams(winW, winH)
        tabParams = params
        applySideStyle()
        root.setOnTouchListener(tabTouchListener)

        val b = tabBounds()
        params.x = if (side == OverlayPrefs.SIDE_LEFT) b.minX else b.maxX
        params.y = b.minY + (OverlayPrefs.yFraction(this) * (b.maxY - b.minY)).toInt()
        wm.addView(root, params)
    }

    private fun positionTab(newSide: Int, yFraction: Float) {
        val params = tabParams ?: return
        val root = tabRoot ?: return
        side = newSide
        val b = tabBounds()
        params.x = if (side == OverlayPrefs.SIDE_LEFT) b.minX else b.maxX
        params.y = b.minY + (yFraction.coerceIn(0f, 1f) * (b.maxY - b.minY)).toInt()
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
        val isDragging get() = dragging

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
                        val b = tabBounds()
                        params.x = (startX + dx).toInt().coerceIn(b.minX, b.maxX)
                        params.y = (startY + dy).toInt().coerceIn(b.minY, b.maxY)
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
        val (sw, _) = screenSize()
        val b = tabBounds()
        val centerX = params.x + winW / 2
        side = if (centerX < sw / 2) OverlayPrefs.SIDE_LEFT else OverlayPrefs.SIDE_RIGHT
        applySideStyle()

        val targetX = if (side == OverlayPrefs.SIDE_LEFT) b.minX else b.maxX
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
                    val range = (b.maxY - b.minY).coerceAtLeast(1)
                    OverlayPrefs.savePosition(
                        this@GameOverlayService, side, (params.y - b.minY).toFloat() / range
                    )
                }
            })
            start()
        }
    }

    // -------------------------------------------------------------- Painel

    private var panelLeft: View? = null
    private var panelRight: View? = null
    private var panelLeftW = 0
    private var panelRightW = 0
    private var closing = false
    private var panelJob: Job? = null
    private var fpsView: TextView? = null
    private var pingView: TextView? = null
    private var volumeStep = -1

    // A.I e Boost só alternam a área de controles da direita para "Em breve" (sem executar nada).
    private var aiButton: View? = null
    private var boostButton: View? = null
    private var controlsView: View? = null
    private var soonView: View? = null
    private var selectedOption = OPTION_NONE

    private fun panelBaseWidth(sw: Int): Int =
        (sw * 0.30f).toInt().coerceIn(dp(210), dp(280)).coerceAtMost((sw * 0.46f).toInt())

    private fun openPanel() {
        if (!gameActive || state == State.OPEN || panelView != null) return
        handler.removeCallbacks(peekReset)
        state = State.OPEN
        closing = false

        val view = buildPanel()
        val params = overlayParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        panelView = view
        panelParams = params

        panelLeft?.translationX = -panelLeftW.toFloat()
        panelRight?.translationX = panelRightW.toFloat()
        wm.addView(view, params)
        tabRoot?.visibility = View.INVISIBLE
        animatePanels(open = true)
        startPanelUpdates()
    }

    private fun closePanel(animated: Boolean = true) {
        val view = panelView ?: return
        if (closing && animated) return
        panelJob?.cancel()
        panelJob = null

        fun finish() {
            runCatching { wm.removeView(view) }
            if (panelView === view) {
                panelView = null
                panelParams = null
                panelLeft = null
                panelRight = null
                fpsView = null
                pingView = null
                aiButton = null
                boostButton = null
                controlsView = null
                soonView = null
                selectedOption = OPTION_NONE
            }
            closing = false
            tabRoot?.visibility = if (gameActive) View.VISIBLE else View.GONE
            setPeekInstant(false)
        }

        if (!animated) {
            finish()
            return
        }
        closing = true
        animatePanels(open = false, onEnd = ::finish)
    }

    /** Metades entram/saem pelas laterais; o centro permanece vazio. */
    private fun animatePanels(open: Boolean, onEnd: (() -> Unit)? = null) {
        val left = panelLeft
        val right = panelRight
        if (left == null || right == null) {
            onEnd?.invoke()
            return
        }
        val duration = if (open) 200L else 160L
        val interpolator = if (open) DecelerateInterpolator() else AccelerateInterpolator()
        left.animate().cancel()
        right.animate().cancel()
        left.animate()
            .translationX(if (open) 0f else -panelLeftW.toFloat())
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withLayer()
            .start()
        right.animate()
            .translationX(if (open) 0f else panelRightW.toFloat())
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withLayer()
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /** Ping e FPS ao vivo, só enquanto o painel está aberto (nada roda com ele fechado). */
    private fun startPanelUpdates() {
        panelJob?.cancel()
        val meter = GameFpsMeter(shizuku)
        panelJob = scope.launch {
            launch {
                while (isActive) {
                    val rtt = withContext(Dispatchers.IO) { PingSampler.measureOnce() }
                    pingView?.text = rtt?.toString() ?: NO_VALUE
                    delay(PING_EVERY_MS)
                }
            }
            launch {
                while (isActive) {
                    val pkg = gamePackage
                    val fps = if (pkg == null) null else withContext(Dispatchers.IO) { meter.sample(pkg) }
                    fpsView?.text = fps?.toString() ?: NO_VALUE
                    delay(FPS_EVERY_MS)
                }
            }
        }
    }

    private fun buildPanel(): FrameLayout {
        val (sw, _) = screenSize()
        val cut = cutoutInsets()
        val base = panelBaseWidth(sw)
        panelLeftW = base + cut.left
        panelRightW = base + cut.right
        val outer = dp(16)
        val top = dp(14) + cut.top
        val bottom = dp(18) + cut.bottom

        // Tocar na área vazia do centro (fora das duas metades) fecha o painel.
        val root = FrameLayout(this).apply { setOnClickListener { closePanel() } }

        val left = SlantedPanel(this, onLeft = true).apply {
            val inner = ((1f - SlantedPanel.TIP_FRACTION) * panelLeftW).toInt() + dp(10)
            addView(
                buildLeftContent().apply { setPadding(cut.left + outer, top, inner, bottom) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        val right = SlantedPanel(this, onLeft = false).apply {
            val inner = ((1f - SlantedPanel.TIP_FRACTION) * panelRightW).toInt() + dp(10)
            addView(
                buildRightContent().apply { setPadding(inner, top, cut.right + outer, bottom) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        root.addView(left, FrameLayout.LayoutParams(panelLeftW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        root.addView(right, FrameLayout.LayoutParams(panelRightW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        root.addView(buildCloseButton(cut.top))
        panelLeft = left
        panelRight = right
        return root
    }

    /** Esquerda: identificação do jogo, FPS e os dois botões (A.I e Boost). */
    private fun buildLeftContent(): LinearLayout {
        val pkg = gamePackage
        val name = runCatching {
            pkg?.let { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
        }.getOrNull() ?: "Free Fire"

        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val drawable = runCatching { pkg?.let { packageManager.getApplicationIcon(it) } }.getOrNull()
            if (drawable != null) {
                setImageDrawable(drawable)
            } else {
                setBackgroundColor(OverlayColors.ORANGE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setImageResource(R.drawable.ic_overlay_bolt)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
        }
        val iconColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(
                label(name, 10f, OverlayColors.TEXT_DARK).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                },
                LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            )
        }

        val fps = label(NO_VALUE, 28f, OverlayColors.TEXT_DARK, light = true).also { fpsView = it }
        val fpsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fps)
            addView(
                label("FPS", 13f, OverlayColors.ORANGE, bold = true).apply { letterSpacing = 0.08f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) }
            )
        }
        val gameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconColumn)
            addView(
                fpsColumn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) }
            )
        }

        val ai = actionButton("A.I", R.drawable.ic_overlay_ai) { selectOption(OPTION_AI) }
        val boost = actionButton("Boost", R.drawable.ic_overlay_bolt) { selectOption(OPTION_BOOST) }
        aiButton = ai
        boostButton = boost

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(Build.MODEL.uppercase(Locale.ROOT), 10f, OverlayColors.TEXT_MUTED, bold = true).apply {
                letterSpacing = 0.1f
            })
            addView(
                label("JOGO ATIVO", 20f, OverlayColors.ORANGE, bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) }
            )
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(gameRow)
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(ai, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
            addView(
                boost,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) }
            )
        }
    }

    /** Direita: latência e a área de controles (volume). */
    private fun buildRightContent(): LinearLayout {
        val ping = label(NO_VALUE, 28f, OverlayColors.TEXT_DARK, light = true).also { pingView = it }

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeStep = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val slider = VerticalLevelView(this, volumeStep.toFloat() / maxVolume) { level ->
            val step = (level * maxVolume).roundToInt().coerceIn(0, maxVolume)
            if (step != volumeStep) {
                volumeStep = step
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0) }
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label("♪", 18f, OverlayColors.TEXT_MUTED, bold = true))
            addView(
                slider,
                LinearLayout.LayoutParams(dp(40), 0, 1f).apply { topMargin = dp(6) }
            )
        }
        // As duas áreas já existem e só alternam visibilidade: nada é reconstruído nem se desloca.
        val soon = label("Em breve", 15f, OverlayColors.TEXT_MUTED, bold = true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            alpha = 0f
        }
        controlsView = controls
        soonView = soon
        val switcher = FrameLayout(this).apply {
            addView(
                controls,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                soon,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("LATÊNCIA", 10f, OverlayColors.TEXT_MUTED, bold = true).apply { letterSpacing = 0.1f })
            addView(
                ping,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) }
            )
            addView(
                label("MS", 13f, OverlayColors.ORANGE, bold = true).apply { letterSpacing = 0.08f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) }
            )
            addView(
                switcher,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) }
            )
        }
    }

    /** Tocar de novo na opção já selecionada volta aos controles. */
    private fun selectOption(option: Int) {
        selectedOption = if (selectedOption == option) OPTION_NONE else option
        aiButton?.background = buttonBackground(selectedOption == OPTION_AI)
        boostButton?.background = buttonBackground(selectedOption == OPTION_BOOST)

        val show = if (selectedOption == OPTION_NONE) controlsView else soonView
        val hide = if (selectedOption == OPTION_NONE) soonView else controlsView
        if (show == null || hide == null) return
        show.animate().cancel()
        hide.animate().cancel()
        show.visibility = View.VISIBLE
        show.animate().alpha(1f).setDuration(120).start()
        hide.animate().alpha(0f).setDuration(120).withEndAction { hide.visibility = View.GONE }.start()
    }

    /** Botão discreto de fechar: alvo de toque de 44 dp, círculo visível de 32 dp, no topo do centro. */
    private fun buildCloseButton(topInset: Int): FrameLayout = FrameLayout(this).apply {
        isClickable = true
        setOnClickListener { closePanel() }
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_overlay_close)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#B3262A35"))
                }
            },
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
        )
    }.also {
        it.layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(6) + topInset }
    }

    /** Texto em dp (não acompanha a escala de fonte do sistema, para o painel não quebrar). */
    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        light: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        typeface = when {
            bold -> Typeface.DEFAULT_BOLD
            light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun actionButton(text: String, iconRes: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = buttonBackground(selected = false)
            isClickable = true
            setOnClickListener { onClick() }
            addView(
                ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(OverlayColors.ORANGE)
                },
                LinearLayout.LayoutParams(dp(16), dp(16))
            )
            addView(
                label(text, 13f, OverlayColors.ORANGE, bold = true).apply { letterSpacing = 0.04f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(6) }
            )
        }

    private fun buttonBackground(selected: Boolean): Drawable {
        val radius = dp(10).toFloat()
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (selected) OverlayColors.ORANGE_SOFT else Color.TRANSPARENT)
            setStroke((1.5f * density).toInt().coerceAtLeast(1), OverlayColors.ORANGE)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(OverlayColors.ORANGE_SOFT), shape, mask)
    }

    // ------------------------------------------------------- Estado do jogo

    private val gamePackages = setOf(
        FreeFireDetector.PKG_FREE_FIRE_STANDARD,
        FreeFireDetector.PKG_FREE_FIRE_MAX
    )

    /** Consulta periodicamente o app em primeiro plano (mesmo mecanismo da aba Logs). */
    private fun startGameMonitor() {
        if (monitorJob?.isActive == true) return
        val detector = collector ?: ActiveAppsCollector(this, shizuku).also { collector = it }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        monitorJob = scope.launch {
            while (isActive) {
                if (power.isInteractive) {
                    val result = withContext(Dispatchers.IO) { detector.foreground() }
                    updateStatusText(
                        when {
                            !result.canDetect -> STATUS_CANNOT_DETECT
                            result.packageName in gamePackages -> STATUS_GAME_OPEN
                            else -> STATUS_WAITING
                        }
                    )
                    if (result.packageName in gamePackages) gamePackage = result.packageName
                    setGameActive(result.packageName in gamePackages)
                }
                delay(GAME_POLL_MS)
            }
        }
    }

    private fun setGameActive(active: Boolean) {
        if (active == gameActive) return
        gameActive = active
        if (active) {
            tabRoot?.visibility = View.VISIBLE
            setPeekInstant(false)
            // O jogo costuma abrir em paisagem: recalcula com as medidas atuais e de novo após assentar.
            repositionTab()
            handler.removeCallbacks(settleReposition)
            handler.postDelayed(settleReposition, SETTLE_MS)
            handler.postDelayed(settleReposition, SETTLE_MS * 3)
        } else {
            closePanel(animated = false)
            tabRoot?.visibility = View.GONE
            setPeekInstant(false)
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

    /** Faixa de posições em que a janela da aba fica inteira na área visível (fora de recortes da tela). */
    private class TabBounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    private fun tabBounds(): TabBounds {
        val (sw, sh) = screenSize()
        val cut = cutoutInsets()
        val minX = cut.left.coerceAtLeast(0)
        val minY = cut.top.coerceAtLeast(0)
        return TabBounds(
            minX = minX,
            maxX = (sw - cut.right - winW).coerceAtLeast(minX),
            minY = minY,
            maxY = (sh - cut.bottom - winH).coerceAtLeast(minY)
        )
    }

    /** Recorte da tela (notch/câmera) na orientação atual; vazio antes do Android 11. */
    private fun cutoutInsets(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.displayCutout())
            return Rect(i.left, i.top, i.right, i.bottom)
        }
        return Rect()
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

    private fun updateStatusText(text: String) {
        if (text == lastStatusText) return
        lastStatusText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, GameOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_bolt)
            .setContentTitle("Painel do jogo ativo")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Fechar painel", stopIntent)
            .build()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Painel do jogo", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val text = lastStatusText ?: STATUS_WAITING
        val notification = buildNotification(text)

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
        private const val NO_VALUE = "--"
        private const val OPTION_NONE = 0
        private const val OPTION_AI = 1
        private const val OPTION_BOOST = 2
        private const val PING_EVERY_MS = 3_000L
        private const val FPS_EVERY_MS = 1_000L
        private const val PEEK_TIMEOUT_MS = 3000L
        private const val GAME_POLL_MS = 1_500L
        private const val SETTLE_MS = 400L

        private const val STATUS_WAITING = "Aguardando o Free Fire abrir"
        private const val STATUS_GAME_OPEN = "Free Fire aberto: painel disponível"
        private const val STATUS_CANNOT_DETECT =
            "Para detectar o jogo, conceda o acesso ao uso ou conecte o Shizuku"

        private val COLOR_ORANGE = Color.parseColor("#FF5E00")

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
