package com.example.ui.screens

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.shizuku.ShizukuStatus
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.FireAmber
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.MainUiState

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onBackClick: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.testTag("settings_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar ao Início",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Configurações",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Conexão
        SectionLabel("Conexão")
        SettingsCard {
            val statusColor = when (uiState.shizukuStatus) {
                ShizukuStatus.CONNECTED -> SuccessGreen
                ShizukuStatus.NO_PERMISSION -> FireAmber
                ShizukuStatus.DISCONNECTED, ShizukuStatus.UNAVAILABLE -> ErrorRed
            }
            val statusHint = when (uiState.shizukuStatus) {
                ShizukuStatus.CONNECTED -> "Pronto para aplicar otimizações."
                ShizukuStatus.NO_PERMISSION -> "Autorize o FF Game Optimizer para liberar os ajustes."
                ShizukuStatus.DISCONNECTED -> "Inicie o serviço no app do Shizuku."
                ShizukuStatus.UNAVAILABLE -> "Instale e inicie o Shizuku. Veja a Ajuda (?)."
            }

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .animateContentSize()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Shizuku",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = statusHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiState.shizukuStatus.label,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = statusColor,
                        textAlign = TextAlign.End
                    )
                }

                if (uiState.shizukuStatus == ShizukuStatus.NO_PERMISSION) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onRequestShizukuPermission,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Solicitar autorização agora")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Aparelho
        SectionLabel("Aparelho")
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SpecItem(label = "Fabricante & Modelo", value = "${uiState.deviceSpec.brand} ${uiState.deviceSpec.model}")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SpecItem(label = "Versão do Sistema", value = "${uiState.deviceSpec.androidVersion} (API ${uiState.deviceSpec.apiLevel})")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SpecItem(label = "Hardware / Chipset", value = uiState.deviceSpec.hardware)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        content()
    }
}

@Composable
private fun SpecItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
