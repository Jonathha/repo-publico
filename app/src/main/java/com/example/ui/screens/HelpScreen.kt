package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(
    onBackClick: () -> Unit,
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
                modifier = Modifier.testTag("help_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar ao Início",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Ajuda",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        HelpCard(
            icon = Icons.Default.Security,
            title = "Instruções do Shizuku",
            tint = MaterialTheme.colorScheme.primary
        ) {
            Text(
                text = "O Shizuku permite que o aplicativo execute otimizações com permissões de depuração ADB sem a necessidade de root.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Como iniciar o Shizuku:",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "1. Instale o aplicativo Shizuku oficial se ainda não o tiver.\n" +
                        "2. No Android 11+: Ative \"Depuração por Wi-Fi\" nas Opções do Desenvolvedor e inicie o serviço direto no app do Shizuku.\n" +
                        "3. No Android 10 ou inferior: Conecte ao PC via cabo USB e execute o comando do Shizuku via adb shell.\n" +
                        "4. Abra o Shizuku e autorize o \"FF Game Optimizer\" na lista de aplicativos autorizados.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        HelpCard(
            icon = Icons.Default.Info,
            title = "Segurança e Integridade",
            tint = MaterialTheme.colorScheme.primary
        ) {
            Text(
                text = "Este aplicativo atua estritamente através das APIs do Android e permissões Shizuku (adb).\n\n" +
                        "• Não modifica arquivos, código ou memória do Free Fire.\n" +
                        "• Não realiza injeção de DLL/SO ou trapaças.\n" +
                        "• Não viola as regras de integridade do jogo ou do sistema.\n" +
                        "• Todas as alterações suportam restauração aos valores originais.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun HelpCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tint: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
