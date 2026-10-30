package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.system.RamDiagnostic
import com.example.data.system.StorageDiagnostic
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.FireAmber
import com.example.ui.theme.SuccessGreen
import java.util.Locale

/** Visão geral do aparelho: RAM, armazenamento e bateria em anéis animados. */
@Composable
fun OverviewRings(
    ramDiagnostic: RamDiagnostic?,
    storageDiagnostic: StorageDiagnostic?,
    batteryPercent: Int?,
    modifier: Modifier = Modifier
) {
    val ramDetail = ramDiagnostic?.let {
        val gb = 1024.0 * 1024.0 * 1024.0
        String.format(Locale.US, "%.1f / %.1f GB", it.usedBytes / gb, it.totalBytes / gb)
    }

    // O diâmetro dos anéis acompanha a largura disponível (margens 2×20dp + padding 2×12dp).
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val ringSize = ((maxWidth - 64.dp) / 3 - 8.dp).coerceIn(64.dp, 84.dp)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricRing(
                    label = "RAM",
                    percent = ramDiagnostic?.usedPercentage,
                    detail = ramDetail,
                    color = if (ramDiagnostic?.isLowMemory == true) ErrorRed else CyanAccent,
                    ringSize = ringSize,
                    modifier = Modifier.weight(1f)
                )
                MetricRing(
                    label = "Armazenamento",
                    percent = storageDiagnostic?.usedPercentage,
                    detail = storageDiagnostic?.summary,
                    color = FireAmber,
                    ringSize = ringSize,
                    modifier = Modifier.weight(1f)
                )
                MetricRing(
                    label = "Bateria",
                    percent = batteryPercent,
                    detail = null,
                    color = if (batteryPercent != null && batteryPercent <= 15) ErrorRed else SuccessGreen,
                    ringSize = ringSize,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricRing(
    label: String,
    percent: Int?,
    detail: String?,
    color: Color,
    ringSize: Dp,
    modifier: Modifier = Modifier
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = remember { Animatable(0f) }
    LaunchedEffect(percent) {
        progress.animateTo(
            (percent ?: 0) / 100f,
            tween(durationMillis = 600, easing = FastOutSlowInEasing)
        )
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            // O progresso é lido apenas na fase de desenho: o anel anima sem recompor.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.1f
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Text(
                text = if (percent != null) "$percent%" else "--",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
