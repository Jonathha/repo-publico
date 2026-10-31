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
    private val busy = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var messageJob: Job? = null

    fun toggle() {
        if (_state.value == AiState.OFF) request() else stop()
    }

    private fun request() {
        if (_state.value != AiState.OFF) return
        _state.value = AiState.REQUESTING
        runCatching { requestPermission() }.onFailure {
            _state.value = AiState.OFF
            say(MSG_FAILED)
        }
    }

    /** A janela de consentimento voltou (ou foi cancelada). */
    fun onPermission(resultCode: Int, data: Intent?) {
        if (_state.value != AiState.REQUESTING) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            _state.value = AiState.OFF
            say(MSG_DENIED)
            return
        }
        val manager = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val created = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (created == null) {
            _state.value = AiState.OFF
            say(MSG_FAILED)
            return
        }
        projection = created
        created.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        _state.value = AiState.LIVE
        startCapture()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Encerrada pelo usuário ou pelo sistema: derruba tudo e volta para OFF.
            releaseCapture()
            projection = null
            _state.value = AiState.OFF
            _boxes.value = emptyList()
        }
    }

    private fun startCapture() {
        val p = projection ?: return
        val (sw, sh) = screenSize()
        val w = (sw * CAPTURE_SCALE).toInt().coerceAtLeast(MIN_CAPTURE)
        val h = (sh * CAPTURE_SCALE).toInt().coerceAtLeast(MIN_CAPTURE)
        val hr = thread ?: HandlerThread("ai-capture").also { it.start(); thread = it }
        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r: ImageReader -> onImage(r) }, Handler(hr.looper))
        }
        reader = imageReader
        display = p.createVirtualDisplay(
            "ai-capture",
            w,
            h,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
    }

    /** O display mudou de tamanho/orientação: refaz o espelho no novo tamanho. */
    fun onDisplayChanged() {
        if (_state.value != AiState.LIVE) return
        releaseCapture()
        startCapture()
    }

    fun stop() {
        if (_state.value == AiState.OFF && projection == null) return
        releaseCapture()
        val p = projection
        projection = null
        runCatching { p?.stop() }
        _state.value = AiState.OFF
        _boxes.value = emptyList()
    }

    fun release() {
        stop()
        messageJob?.cancel()
    }

    private fun releaseCapture() {
        busy.set(false)
        runCatching { display?.release() }
        display = null
        runCatching {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        }
        reader = null
        runCatching { thread?.quitSafely() }
        thread = null
    }

    private fun onImage(imageReader: ImageReader) {
        val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
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

    private companion object {
        const val CAPTURE_SCALE = 0.25f
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
