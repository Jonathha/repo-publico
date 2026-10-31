package com.example.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AiState { OFF, REQUESTING, LIVE }

/** Área detectada, em coordenadas normalizadas (0..1) da tela. */
internal data class Hitbox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * A.I de tela: captura o espelho do display via MediaProjection (sem injeção no jogo), analisa os
 * quadros e publica as áreas dos oponentes para o overlay desenhar as caixas por cima.
 *
 * O consentimento de captura é pedido por [ScreenCaptureActivity]; o resultado volta pelo serviço,
 * que chama [onPermission]. Nada roda com o estado OFF.
 */
internal class AiController(
    context: Context,
    private val scope: CoroutineScope,
    private val requestPermission: () -> Unit
) {
    private val app = context.applicationContext
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val densityDpi = app.resources.displayMetrics.densityDpi

    private val _state = MutableStateFlow(AiState.OFF)
    val state: StateFlow<AiState> = _state.asStateFlow()

    private val _boxes = MutableStateFlow<List<Hitbox>>(emptyList())
    val boxes: StateFlow<List<Hitbox>> = _boxes.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var captureW = 0
    private var captureH = 0
    private val busy = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var messageJob: Job? = null
    private var permissionAsked = false
    private var startWhenGranted = false
    private var messageOnDenied = false
    private var pendingGrant: (() -> Unit)? = null

    /** Autorização já obtida (a sessão fica guardada mesmo com a análise desligada). */
    fun isAuthorized(): Boolean = projection != null

    fun toggle() {
        if (_state.value == AiState.OFF) {
            if (projection != null) beginAnalysis() else request()
        } else {
            stop()
        }
    }

    /**
     * Pede o consentimento agora, com o app em primeiro plano (ao ativar o painel), e guarda a
     * sessão. Assim, dentro do jogo a A.I liga sem abrir o app nem pedir a tela de novo.
     * [onGranted] roda depois que a captura já está ativa, para abrir o jogo sem interrupção.
     */
    fun prepare(onGranted: (() -> Unit)? = null) {
        if (permissionAsked || projection != null || _state.value != AiState.OFF) return
        permissionAsked = true
        startWhenGranted = false
        messageOnDenied = false
        pendingGrant = onGranted
        _state.value = AiState.REQUESTING
        runCatching { requestPermission() }.onFailure {
            permissionAsked = false
            pendingGrant = null
            _state.value = AiState.OFF
        }
    }

    private fun request() {
        if (_state.value != AiState.OFF) return
        startWhenGranted = true
        messageOnDenied = true
        if (projection != null) {
            beginAnalysis()
            return
        }
        permissionAsked = true
        _state.value = AiState.REQUESTING
        runCatching { requestPermission() }.onFailure {
            permissionAsked = false
            _state.value = AiState.OFF
            say(MSG_FAILED)
        }
    }

    /** A janela de consentimento voltou (ou foi cancelada). */
    fun onPermission(resultCode: Int, data: Intent?) {
        val requested = _state.value == AiState.REQUESTING
        // Serviço recriado enquanto a janela do sistema estava aberta: aceita o resultado válido
        // em vez de perder a autorização. Resultado repetido com sessão viva é ignorado.
        if (!requested && (resultCode != Activity.RESULT_OK || data == null || projection != null)) return
        val startNow = startWhenGranted
        startWhenGranted = false
        if (resultCode != Activity.RESULT_OK || data == null) {
            _state.value = AiState.OFF
            permissionAsked = false
            pendingGrant = null
            if (messageOnDenied) say(MSG_DENIED)
            messageOnDenied = false
            return
        }
        val manager = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val created = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (created == null) {
            _state.value = AiState.OFF
            permissionAsked = false
            pendingGrant = null
            say(MSG_FAILED)
            return
        }
        projection = created
        created.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        messageOnDenied = false
        _state.value = AiState.OFF
        if (startNow) {
            beginAnalysis()
        } else if (pendingGrant != null) {
            // Consentimento pedido ao ativar o painel: a tela já começa a ser capturada e, só
            // depois de a captura estar de pé, o jogo é aberto (nada de app durante a partida).
            beginAnalysis()
            val launch = pendingGrant
            pendingGrant = null
            if (_state.value == AiState.LIVE) launch?.invoke()
        }
    }

    private fun beginAnalysis() {
        if (projection == null) {
            request()
            return
        }
        if (_state.value == AiState.LIVE) return
        _state.value = AiState.LIVE
        startCapture()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Encerrada pelo usuário ou pelo sistema: derruba tudo e volta para OFF.
            releaseCapture()
            projection = null
            permissionAsked = false
            startWhenGranted = false
            pendingGrant = null
            _state.value = AiState.OFF
            _boxes.value = emptyList()
        }
    }

    /**
     * Liga a captura. O Android 14 permite um único VirtualDisplay por sessão de MediaProjection,
     * então o display é criado uma vez e reaproveitado: na rotação só muda o tamanho/superfície.
     */
    private fun startCapture() {
        val p = projection ?: return
        val (sw, sh) = screenSize()
        val w = (sw * CAPTURE_SCALE).toInt().coerceAtLeast(MIN_CAPTURE)
        val h = (sh * CAPTURE_SCALE).toInt().coerceAtLeast(MIN_CAPTURE)
        val hr = thread ?: HandlerThread("ai-capture").also { it.start(); thread = it }
        val existing = display

        if (existing == null) {
            val imageReader = newReader(w, h, hr)
            reader = imageReader
            display = runCatching {
                p.createVirtualDisplay(
                    "ai-capture",
                    w,
                    h,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    null
                )
            }.getOrNull() ?: run {
                closeReader(imageReader)
                say(MSG_FAILED)
                _state.value = AiState.OFF
                return
            }
            captureW = w
            captureH = h
            return
        }

        if (w == captureW && h == captureH) {
            // Mesmo tamanho: o leitor continua ligado e só volta a analisar.
            return
        }

        // Rotação/mudança de tamanho: reaproveita o mesmo VirtualDisplay.
        val imageReader = newReader(w, h, hr)
        val resized = runCatching {
            existing.resize(w, h, densityDpi)
            existing.surface = imageReader.surface
        }.isSuccess
        if (!resized) {
            closeReader(imageReader)
            say(MSG_FAILED)
            _state.value = AiState.OFF
            return
        }
        closeReader(reader)
        reader = imageReader
        captureW = w
        captureH = h
    }

    private fun newReader(w: Int, h: Int, hr: HandlerThread): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r: ImageReader -> onImage(r) }, Handler(hr.looper))
        }

    private fun closeReader(target: ImageReader?) {
        runCatching {
            target?.setOnImageAvailableListener(null, null)
            target?.close()
        }
    }

    /** O display mudou de tamanho/orientação: refaz o espelho no novo tamanho. */
    fun onDisplayChanged() {
        if (_state.value != AiState.LIVE) return
        startCapture()
    }

    /**
     * Para a análise e apaga as caixas. A autorização da captura NÃO é descartada: religar a A.I
     * ou voltar ao jogo não abre a janela de consentimento de novo.
     */
    fun stop() {
        startWhenGranted = false
        pauseCapture()
        _boxes.value = emptyList()
        if (_state.value != AiState.OFF) _state.value = AiState.OFF
    }

    /** Serviço encerrando: aí sim descarta a sessão de captura. */
    fun release() {
        startWhenGranted = false
        pendingGrant = null
        releaseCapture()
        val p = projection
        projection = null
        runCatching { p?.stop() }
        _state.value = AiState.OFF
        _boxes.value = emptyList()
        messageJob?.cancel()
    }

    /** Pausa a análise sem destruir o VirtualDisplay (que não pode ser recriado). */
    private fun pauseCapture() {
        busy.set(false)
    }

    private fun releaseCapture() {
        busy.set(false)
        runCatching { display?.release() }
        display = null
        closeReader(reader)
        reader = null
        captureW = 0
        captureH = 0
        runCatching { thread?.quitSafely() }
        thread = null
    }

    private fun onImage(imageReader: ImageReader) {
        val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
        // Análise desligada: só esvazia a fila para o espelho continuar fluindo.
        if (_state.value != AiState.LIVE) {
            image.close()
            return
        }
        val now = SystemClock.uptimeMillis()
        // Quadros demais para a heurística: mantém ~10 por segundo e descarta o resto.
        if (now - lastFrameAt < FRAME_INTERVAL_MS || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt = now
        val bitmap = runCatching { image.toBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) {
            busy.set(false)
            return
        }
        scope.launch {
            val found = withContext(Dispatchers.Default) { AiDetector.detect(bitmap) }
            bitmap.recycle()
            if (_state.value == AiState.LIVE) _boxes.value = found
            busy.set(false)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun say(text: String) {
        _message.value = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(MESSAGE_MS)
            if (_message.value == text) _message.value = null
        }
    }

    /** Aviso pontual no painel, sem mexer no estado da captura. */
    fun warn(text: String) = say(text)

    private companion object {
        // Metade da resolução real: marcadores distantes continuam com pixels suficientes.
        const val CAPTURE_SCALE = 0.5f
        const val MIN_CAPTURE = 96
        const val FRAME_INTERVAL_MS = 95L
        const val MESSAGE_MS = 3_500L
        const val MSG_DENIED = "Captura de tela não autorizada"
        const val MSG_FAILED = "Não foi possível iniciar a captura de tela"
    }
}

/** Converte o quadro do MediaProjection em Bitmap, descartando o preenchimento entre linhas. */
private fun android.media.Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val paddedW = width + rowPadding / pixelStride
    val padded = Bitmap.createBitmap(paddedW, height, Bitmap.Config.ARGB_8888)
    plane.buffer.rewind()
    padded.copyPixelsFromBuffer(plane.buffer)
    if (paddedW == width) return padded
    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    return cropped
}
