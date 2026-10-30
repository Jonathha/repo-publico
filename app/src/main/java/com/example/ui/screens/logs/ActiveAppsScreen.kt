package com.example.ui.screens.logs

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.data.logs.ActiveApp
import com.example.data.logs.ActiveSource
import com.example.data.logs.ActiveState
import com.example.data.logs.UsageAccess
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.LogsViewModel

@Composable
fun ActiveAppsScreen(
    viewModel: LogsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.apps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var confirmKill by remember { mutableStateOf<ActiveApp?>(null) }

    // Atualiza só com a tela visível e o app em primeiro plano.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollActiveApps() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LogsHeader(
            title = "Aplicativos ativos",
            subtitle = "Atualiza a cada 3 segundos",
            onBack = onBack
        )

        val snapshot = state.snapshot
        val canKill = snapshot.source == ActiveSource.SHIZUKU

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (snapshot.source) {
                ActiveSource.SHIZUKU -> Unit
                ActiveSource.USAGE_ACCESS -> InfoBanner(
                    "Sem o Shizuku só é possível ver o app em primeiro plano, e não dá para encerrar apps."
                )
                ActiveSource.NONE -> if (state.loaded) InfoBanner(
                    text = "O Android não mostra apps de terceiros sem o Shizuku conectado ou o acesso ao uso. " +
                        "Conecte o Shizuku ou conceda o acesso ao uso.",
                    actionLabel = "Acesso ao uso",
                    onAction = { openUsageAccess(context) }
                )
            }
            state.message?.let {
                InfoBanner(
                    text = it,
                    isError = state.messageIsError,
                    actionLabel = "OK",
                    onAction = viewModel::dismissAppsMessage
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (snapshot.apps.isEmpty()) {
            if (state.loaded && snapshot.source != ActiveSource.NONE) {
                EmptyState(Icons.Rounded.Apps, "Nenhum aplicativo ativo encontrado agora.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(snapshot.apps, key = { it.packageName }) { app ->
                    ActiveAppRow(
                        app = app,
                        canKill = canKill,
                        isKilling = app.packageName in state.killing,
                        onKill = { confirmKill = app }
                    )
                }
            }
        }
    }

    confirmKill?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmKill = null },
            title = { Text("Encerrar ${app.label}?") },
            text = { Text("O app será forçado a parar e pode perder dados não salvos.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmKill = null
                    viewModel.killApp(app.packageName)
                }) { Text("Encerrar") }
            },
            dismissButton = { TextButton(onClick = { confirmKill = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ActiveAppRow(
    app: ActiveApp,
    canKill: Boolean,
    isKilling: Boolean,
    onKill: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = app.packageName)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (app.state == ActiveState.FOREGROUND) {
                        StatusChip("Em primeiro plano", SuccessGreen)
                    } else {
                        StatusChip("Em execução", MaterialTheme.colorScheme.secondary)
                    }
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val details = buildList {
                    app.activeForMs?.let {
                        add(if (app.state == ActiveState.FOREGROUND && app.processCount == 0) "em uso há ${formatDuration(it)}" else "processo há ${formatDuration(it)}")
                    }
                    app.memoryKb?.let { add(formatMemory(it)) }
                    if (app.processCount > 1) add("${app.processCount} processos")
                }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (canKill) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onKill,
                    enabled = !isKilling,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (isKilling) "…" else "Encerrar", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun openUsageAccess(context: Context) {
    try {
        context.startActivity(UsageAccess.settingsIntent())
    } catch (_: Exception) {
    }
}
