package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.shizuku.ShizukuStatus

/** Etapas do modal de permissões exibido ao ativar o painel no jogo. */
enum class OverlayDialogState { NONE, ASK, NOTIFICATIONS_DENIED }

@Composable
fun OverlayPermissionDialog(
    state: OverlayDialogState,
    needsNotifications: Boolean,
    needsOverlay: Boolean,
    needsBrightness: Boolean,
    fpsStatus: ShizukuStatus,
    onContinue: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        OverlayDialogState.NONE -> Unit

        OverlayDialogState.ASK -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Permissões do painel") },
            text = {
                Column {
                    val items = buildList {
                        if (needsNotifications) {
                            add(
                                "Notificações" to
                                    "O painel roda como serviço e mostra uma notificação com o botão para fechá-lo."
                            )
                        }
                        if (needsOverlay) {
                            add(
                                "Sobreposição a outros apps" to
                                    "Necessária para exibir a aba e o painel sobre o jogo. " +
                                    "Você será levado às configurações do Android para permitir."
                            )
                        }
                        if (needsBrightness) {
                            add(
                                "Brilho da tela (opcional)" to
                                    "Permite ajustar o brilho pelo painel. " +
                                    "Você será levado às configurações do Android para permitir."
                            )
                        }
                        if (fpsStatus != ShizukuStatus.CONNECTED) {
                            add(
                                "FPS do jogo (opcional)" to
                                    if (fpsStatus == ShizukuStatus.NO_PERMISSION) {
                                        "Para medir o FPS, autorize este app no Shizuku. O pedido aparece em seguida."
                                    } else {
                                        "Para medir o FPS, o Shizuku precisa estar instalado e em execução. " +
                                            "Sem ele o painel funciona, mas o FPS aparece como --."
                                    }
                            )
                        }
                    }
                    items.forEachIndexed { index, (title, description) ->
                        if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                        PermissionItem(title = title, description = description)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onContinue) { Text("Continuar") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Agora não") } }
        )

        OverlayDialogState.NOTIFICATIONS_DENIED -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Notificações negadas") },
            text = {
                Text(
                    "Sem a permissão de notificações o painel não é ativado. " +
                        "Permita-a nas configurações do app e ative o painel novamente."
                )
            },
            confirmButton = { TextButton(onClick = onOpenNotificationSettings) { Text("Abrir configurações") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun PermissionItem(title: String, description: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
