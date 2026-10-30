package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.navigation.AppScreen

@Composable
fun BottomNavBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer
    )

    val topBorderColor = MaterialTheme.colorScheme.outlineVariant

    NavigationBar(
        modifier = modifier.drawBehind {
            drawLine(
                color = topBorderColor,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx()
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        NavigationBarItem(
            selected = currentScreen == AppScreen.HOME,
            onClick = { if (currentScreen != AppScreen.HOME) onNavigate(AppScreen.HOME) },
            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
            label = { Text("Início") },
            modifier = Modifier.testTag("nav_home"),
            colors = itemColors
        )

        NavigationBarItem(
            selected = currentScreen == AppScreen.FREE_FIRE,
            onClick = { if (currentScreen != AppScreen.FREE_FIRE) onNavigate(AppScreen.FREE_FIRE) },
            icon = { Icon(Icons.Rounded.SportsEsports, contentDescription = null) },
            label = { Text("Free Fire") },
            modifier = Modifier.testTag("nav_freefire"),
            colors = itemColors
        )

        NavigationBarItem(
            selected = currentScreen == AppScreen.TWEAKS,
            onClick = { if (currentScreen != AppScreen.TWEAKS) onNavigate(AppScreen.TWEAKS) },
            icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
            label = { Text("Ajustes") },
            modifier = Modifier.testTag("nav_tweaks"),
            colors = itemColors
        )

        NavigationBarItem(
            selected = currentScreen == AppScreen.HISTORY,
            onClick = { if (currentScreen != AppScreen.HISTORY) onNavigate(AppScreen.HISTORY) },
            icon = { Icon(Icons.Rounded.History, contentDescription = null) },
            label = { Text("Histórico") },
            modifier = Modifier.testTag("nav_history"),
            colors = itemColors
        )
    }
}
