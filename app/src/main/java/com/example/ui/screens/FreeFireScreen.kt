package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.freefire.FreeFireDetector
import com.example.data.shizuku.ShizukuStatus
import com.example.data.tweaks.TweakCatalog
import com.example.ui.theme.CyanAccent
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = "Detecção de Jogo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Detecção no Dispositivo",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    val badgeColor = if (isAnyInstalled) SuccessGreen else Color.Gray
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAnyInstalled) "Detectado" else "Não instalado",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isAnyInstalled) {
                    Text(
                        text = "Versão selecionada para otimização:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (pkg in installedApps) {
                            FilterChip(
                                selected = uiState.selectedPackage == pkg.packageName,
                                onClick = { onSelectPackage(pkg.packageName) },
                                label = {
                                    Text(if (pkg.isMax) "Free Fire MAX" else "Free Fire Padrão")
                                },
                                leadingIcon = if (uiState.selectedPackage == pkg.packageName) {
                                    {
                                        Icon(
                                            Icons.Default.CheckCircle,
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
                } else {
                    Text(
                        text = "Nenhuma instalação de Free Fire ou Free Fire MAX foi encontrada neste dispositivo. O aplicativo verificou os pacotes com.dts.freefireth e com.dts.freefiremax.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isAnyInstalled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onLaunchGame() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("launch_freefire_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Abrir Free Fire",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Iniciar Free Fire", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Otimizações dedicadas ao Free Fire
        Text(
            text = "Ações para a Sessão de Jogo",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Ajuste 1: Compilação ART Speed
        val artTweak = uiState.tweaks.firstOrNull { it.id == TweakCatalog.ID_ART_COMPILE }
        val isArtApplied = uiState.appliedTweakIds.contains(TweakCatalog.ID_ART_COMPILE)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Compilação AOT (Speed)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isArtApplied) {
                        Text(
                            text = "Ativo",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Pré-compila o código DEX do Free Fire em binário de máquina. Elimina stutter e compilação em tempo de execução durante trocas de tiros.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isArtApplied) {
                        OutlinedButton(
                            onClick = { onRestoreTweak(TweakCatalog.ID_ART_COMPILE) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Restaurar padrão")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Button(
                        onClick = { onApplyTweak(TweakCatalog.ID_ART_COMPILE) },
                        enabled = uiState.shizukuStatus == ShizukuStatus.CONNECTED && isAnyInstalled,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isArtApplied) "Recompilar" else "Compilar AOT")
                    }
                }
            }
        }

        // Ajuste 2: Game Mode (Android 12+)
        val gameModeTweak = uiState.tweaks.firstOrNull { it.id == TweakCatalog.ID_GAME_MODE }
        val isGameModeApplied = uiState.appliedTweakIds.contains(TweakCatalog.ID_GAME_MODE)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Game Mode Performance (API Nativa)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isGameModeApplied) {
                        Text(
                            text = "Ativo",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Ativa o modo nativo de jogo do Android 12+ especificamente para o pacote do Free Fire, solicitando ao sistema prioridade de agendamento de CPU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (gameModeTweak?.isSupported == false) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = gameModeTweak.unsupportedReason ?: "Indisponível neste dispositivo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isGameModeApplied) {
                        OutlinedButton(
                            onClick = { onRestoreTweak(TweakCatalog.ID_GAME_MODE) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Restaurar padrão")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Button(
                        onClick = { onApplyTweak(TweakCatalog.ID_GAME_MODE) },
                        enabled = gameModeTweak?.isSupported == true,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Ativar Game Mode")
                    }
                }
            }
        }
    }
}
