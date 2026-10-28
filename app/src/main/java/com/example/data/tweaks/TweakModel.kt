package com.example.data.tweaks

enum class TweakCategory(val title: String) {
    PERFORMANCE("Desempenho & Compilação ART"),
    DISPLAY("Tela & Taxa de Atualização"),
    LATENCY("Latência & Resposta ao Toque"),
    MEMORY("Memória & Sistema")
}

data class TweakItem(
    val id: String,
    val title: String,
    val description: String,
    val technicalDetails: String,
    val category: TweakCategory,
    val requiresShizuku: Boolean = true,
    val minSdk: Int = 24,
    val isSupported: Boolean = true,
    val unsupportedReason: String? = null,
    val isReversible: Boolean = true,
    val isApplied: Boolean = false,
    val backupValue: String? = null
)
