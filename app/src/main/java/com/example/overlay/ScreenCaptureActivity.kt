package com.example.overlay

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Só existe para a janela de consentimento da captura de tela. Fica transparente, sem interface:
 * devolve o resultado ao [GameOverlayService] e se fecha. Sem autorização, a A.I volta para desligada.
 */
class ScreenCaptureActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        GameOverlayService.startAiCapture(this, result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }
}
