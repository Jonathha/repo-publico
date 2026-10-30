package com.example.ui.screens.logs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.logs.PingBucket
import com.example.data.logs.PingSampler
import com.example.data.logs.PingStats
import com.example.ui.theme.ErrorRed
import com.example.ui.viewmodel.LogsViewModel
import com.example.ui.viewmodel.PingRange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingScreen(
    viewModel: LogsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.ping.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        LogsHeader(
            title = "Ping",
            subtitle = "Latência TCP até ${PingSampler.HOST}:${PingSampler.PORT}",
            onBack = onBack
        )

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PingRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { viewModel.setPingRange(range) },
                        label = { Text(range.label) }
                    )
                }
            }
            InfoBanner(
                "Mede a latência do aparelho até esse servidor, não a de uma partida. " +
                    "Coleta a cada 30 s só com o app aberto."
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.loaded && state.stats.total == 0) {
            EmptyState(Icons.Rounded.NetworkCheck, "Nenhuma medição neste período ainda.")
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val stats = state.stats
            val lastRtt = state.last?.rttMs
            val noReply = state.last != null && lastRtt == null

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "Atual",
                    value = if (noReply) "Sem resposta" else lastRtt?.let { "$it ms" } ?: "—",
                    valueColor = if (noReply) ErrorRed else null,
                    modifier = Modifier.weight(1f)
                )
                StatTile("Média", formatMs(stats.avgMs), modifier = Modifier.weight(1f))
                StatTile("Jitter", formatMs(stats.jitterMs), modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Mínimo", stats.minMs?.let { "$it ms" } ?: "—", modifier = Modifier.weight(1f))
                StatTile("Máximo", stats.maxMs?.let { "$it ms" } ?: "—", modifier = Modifier.weight(1f))
                StatTile(
                    label = "Falhas",
                    value = failureText(stats),
                    valueColor = if (stats.failed > 0) ErrorRed else null,
                    modifier = Modifier.weight(1f)
                )
            }

            PingChart(buckets = state.buckets, range = state.range)
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = valueColor ?: MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PingChart(buckets: List<PingBucket>, range: PingRange, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val failColor = ErrorRed
    val maxAvg = (buckets.mapNotNull { it.avgMs }.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val labelFormat = remember(range) {
        SimpleDateFormat(if (range.windowMs > 24 * 60 * 60 * 1000L) "dd/MM HH:mm" else "HH:mm", Locale.getDefault())
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Latência média por período",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "máx. ${maxAvg.roundToInt()} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                if (buckets.isEmpty()) return@Canvas
                val gap = 4.dp.toPx()
                val barW = (size.width - gap * (buckets.size - 1)) / buckets.size
                val radius = CornerRadius(3.dp.toPx())
                val stub = 3.dp.toPx()
                buckets.forEachIndexed { i, b ->
                    val x = i * (barW + gap)
                    val avg = b.avgMs
                    when {
                        b.count == 0 -> drawRoundRect(
                            color = emptyColor,
                            topLeft = Offset(x, size.height - stub),
                            size = Size(barW, stub),
                            cornerRadius = radius
                        )
                        avg == null -> drawRoundRect(
                            color = failColor,
                            topLeft = Offset(x, size.height - stub * 2),
                            size = Size(barW, stub * 2),
                            cornerRadius = radius
                        )
                        else -> {
                            val h = (size.height * (avg / maxAvg).toFloat()).coerceAtLeast(stub)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(x, size.height - h),
                                size = Size(barW, h),
                                cornerRadius = radius
                            )
                            if (b.failed > 0) drawRoundRect(
                                color = failColor,
                                topLeft = Offset(x, 0f),
                                size = Size(barW, stub),
                                cornerRadius = radius
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val first = buckets.firstOrNull()?.startMs
                Text(
                    text = first?.let { labelFormat.format(Date(it)) } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "agora",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Vermelho: período com falhas ou sem resposta.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatMs(v: Double?): String = v?.let { "${it.roundToInt()} ms" } ?: "—"

private fun failureText(s: PingStats): String {
    val rate = s.failureRate ?: return "—"
    return String.format(Locale.getDefault(), "%.1f%% (%d/%d)", rate, s.failed, s.total)
}
