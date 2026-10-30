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

/** Etapas do modal de permissões exibido ao ativar o painel no jogo. */
enum class OverlayDialogState { NONE, ASK, NOTIFICATIONS_DENIED }

@Composable
fun OverlayPermissionDialog(
    state: OverlayDialogState,
    needsNotifications: Boolean,
    needsOverlay: Boolean,
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
                    if (needsNotifications) {
                        PermissionItem(
                            title = "Notificações",
                            description = "O painel roda como serviço e mostra uma notificação com o botão para fechá-lo."
                        )
                    }
                    if (needsNotifications && needsOverlay) Spacer(modifier = Modifier.height(12.dp))
                    if (needsOverlay) {
                        PermissionItem(
                            title = "Sobreposição a outros apps",
                            description = "Necessária para exibir a aba e o painel sobre o jogo. " +
                                "Você será levado às configurações do Android para permitir."
                        )
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
