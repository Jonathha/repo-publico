package com.example.ui.navigation

enum class AppScreen {
    HOME,
    FREE_FIRE,
    TWEAKS,
    HISTORY,
    SETTINGS,
    HELP,
    LOGS_APPS
}

/** Telas internas da aba Logs (a aba HISTORY é o hub). */
val AppScreen.isLogsSub: Boolean
    get() = this == AppScreen.LOGS_APPS

/** Destino do botão Voltar do sistema para cada tela. */
fun AppScreen.backTarget(): AppScreen = if (isLogsSub) AppScreen.HISTORY else AppScreen.HOME
