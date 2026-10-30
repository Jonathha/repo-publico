package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.shizuku.ShizukuStatus
import com.example.data.system.RamDiagnostic
import com.example.data.system.ThermalDiagnostic
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.FireAmber
import com.example.ui.theme.SuccessGreen

@Composable
fun DevicePanel(
    shizukuStatus: ShizukuStatus,
    ramDiagnostic: RamDiagnostic?,
    thermalDiagnostic: ThermalDiagnostic?,
    modifier: Modifier = Modifier
) {
    val (targetStatusColor, statusText) = when (shizukuStatus) {
        ShizukuStatus.CONNECTED -> SuccessGreen to "Conectado"
        ShizukuStatus.NO_PERMISSION -> FireAmber to "Sem permissão"
        ShizukuStatus.DISCONNECTED -> ErrorRed to "Desconectado"
        ShizukuStatus.UNAVAILABLE -> Color.Gray to "Indisponível"
    }
    val statusColor by animateColorAsState(targetStatusColor, tween(200), label = "shizukuBadge")

    val thermalSevere = thermalDiagnostic?.isSevereOrCritical == true
    val tempText = if (thermalDiagnostic?.batteryTempCelsius != null) {
        String.format(java.util.Locale.US, "%.1f°C", thermalDiagnostic.batteryTempCelsius)
    } else {
        thermalDiagnostic?.thermalStatusText ?: "--"
    }
    val thermalSubtitle = thermalDiagnostic?.thermalStatusText ?: "Sensor ativo"

    // Dois tiles de mesma altura: o texto de um nunca desalinha o outro.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusTile(
            label = "SHIZUKU",
            icon = Icons.Rounded.Security,
            iconTint = statusColor,
            value = statusText,
            valueColor = statusColor,
            subtitle = "Acesso privilegiado ADB",
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        StatusTile(
            label = "TÉRMICO",
            icon = Icons.Rounded.Thermostat,
            iconTint = if (thermalSevere) ErrorRed else FireAmber,
            value = tempText,
            valueColor = if (thermalSevere) ErrorRed else MaterialTheme.colorScheme.onSurface,
            subtitle = thermalSubtitle,
            subtitleColor = if (thermalSevere) ErrorRed else SuccessGreen,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun StatusTile(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    value: String,
    valueColor: Color,
    subtitle: String,
    subtitleColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconTint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = valueColor
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    color = subtitleColor
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
