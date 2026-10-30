package com.example.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.tweaks.TweakCatalog
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.MainUiState

@Composable
fun FreeFireScreen(
    uiState: MainUiState,
    onSelectPackage: (String) -> Unit,
    onApplyTweak: (String) -> Unit,
    onRestoreTweak: (String) -> Unit,
    onLaunchGame: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val installedApps = uiState.freeFirePackages.filter { it.isInstalled }
    val isAnyInstalled = installedApps.isNotEmpty()

    // Falha ao abrir o jogo é sinalizada na tela; some ao trocar a versão selecionada.
    var launchFailed by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.selectedPackage) { launchFailed = false }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp)
    ) {
        Text(
            text = "Modo Free Fire",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Otimizações de sistema e perfil de execução dedicados exclusivamente ao Free Fire.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // Cartão de detecção do Free Fire
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(200)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconBox(icon = Icons.Rounded.SportsEsports)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Detecção no Dispositivo",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusBadge(
                        text = if (isAnyInstalled) "Detectado" else "Não instalado",
                        color = if (isAnyInstalled) SuccessGreen else Color.Gray
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isAnyInstalled) {
                    Text(
                        text = "Versão selecionada para otimização:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Chips dividem a largura por igual: não estouram em telas estreitas.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (pkg in installedApps) {
                            val selected = uiState.selectedPackage == pkg.packageName
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectPackage(pkg.packageName) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        text = if (pkg.isMax) "Free Fire MAX" else "Free Fire Padrão",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    // Informações do pacote selecionado
                    val currentPkg = uiState.freeFirePackages.firstOrNull { it.packageName == uiState.selectedPackage }
                    if (currentPkg != null && currentPkg.isInstalled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Pacote: ${currentPkg.packageName}\nVersão: ${currentPkg.versionName} (build ${currentPkg.versionCode})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { launchFailed = !onLaunchGame() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("launch_freefire_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Iniciar Free Fire", fontWeight = FontWeight.Bold)
                    }

                    if (launchFailed) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Não foi possível abrir o jogo. Verifique se a versão selecionada está instalada.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(
                        text = "Nenhuma instalação de Free Fire ou Free Fire MAX foi encontrada neste dispositivo. O aplicativo verificou os pacotes com.dts.freefireth e com.dts.freefiremax.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Otimizações dedicadas ao Free Fire
        Text(
            text = "Ações para a Sessão de Jogo",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Ajuste 1: Compilação ART Speed
        val artTweak = uiState.tweaks.firstOrNull { it.id == TweakCatalog.ID_ART_COMPILE }
        FreeFireTweakCard(
            title = "Compilação AOT (Speed)",
            description = "Pré-compila o código DEX do Free Fire em binário de máquina. Elimina stutter e compilação em tempo de execução durante trocas de tiros.",
            icon = Icons.Rounded.Bolt,
            applied = uiState.appliedTweakIds.contains(TweakCatalog.ID_ART_COMPILE),
            supported = artTweak?.isSupported == true,
            unsupportedReason = artTweak?.unsupportedReason,
            busy = TweakCatalog.ID_ART_COMPILE in uiState.busyTweakIds,
            enabled = !uiState.isOptimizing,
            applyLabel = "Compilar AOT",
            reapplyLabel = "Recompilar",
            onApply = { onApplyTweak(TweakCatalog.ID_ART_COMPILE) },
            onRestore = { onRestoreTweak(TweakCatalog.ID_ART_COMPILE) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Ajuste 2: Game Mode (Android 12+)
        val gameModeTweak = uiState.tweaks.firstOrNull { it.id == TweakCatalog.ID_GAME_MODE }
        FreeFireTweakCard(
            title = "Game Mode Performance (API Nativa)",
            description = "Ativa o modo nativo de jogo do Android 12+ especificamente para o pacote do Free Fire, solicitando ao sistema prioridade de agendamento de CPU.",
            icon = Icons.Rounded.Speed,
            applied = uiState.appliedTweakIds.contains(TweakCatalog.ID_GAME_MODE),
            supported = gameModeTweak?.isSupported == true,
            unsupportedReason = gameModeTweak?.unsupportedReason,
            busy = TweakCatalog.ID_GAME_MODE in uiState.busyTweakIds,
            enabled = !uiState.isOptimizing,
            applyLabel = "Ativar Game Mode",
            reapplyLabel = null,
            onApply = { onApplyTweak(TweakCatalog.ID_GAME_MODE) },
            onRestore = { onRestoreTweak(TweakCatalog.ID_GAME_MODE) }
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Card de um ajuste do Free Fire. Quando ativo mostra "Restaurar padrão" (e, se houver,
 * a ação de reaplicar); quando inativo mostra só a ação de aplicar. Botões ficam
 * desabilitados com indicador de progresso enquanto o ajuste está em execução.
 */
@Composable
private fun FreeFireTweakCard(
    title: String,
    description: String,
    icon: ImageVector,
    applied: Boolean,
    supported: Boolean,
    unsupportedReason: String?,
    busy: Boolean,
    enabled: Boolean,
    applyLabel: String,
    reapplyLabel: String?,
    onApply: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canAct = supported && enabled && !busy
    val primaryLabel = if (applied) reapplyLabel else applyLabel

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(200)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBox(icon = icon)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (applied) {
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusBadge(text = "Ativo", color = SuccessGreen)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!supported && unsupportedReason != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = unsupportedReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (applied) {
                    OutlinedButton(
                        onClick = onRestore,
                        enabled = canAct,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Restaurar padrão", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (primaryLabel != null) {
                    Button(
                        onClick = onApply,
                        enabled = canAct,
                        modifier = if (applied) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(primaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Ícone em caixa de 40dp (ícone de 22dp), mesmo padrão dos cards da Home. */
@Composable
private fun IconBox(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = color
            ),
            maxLines = 1
        )
    }
}
