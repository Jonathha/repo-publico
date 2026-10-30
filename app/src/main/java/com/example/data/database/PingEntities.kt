package com.example.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Medição de latência TCP até [target]. [rttMs] é nulo quando a conexão falhou ou expirou.
 */
@Entity(tableName = "ping_samples", indices = [Index("timestamp")])
data class PingSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val target: String,
    val rttMs: Int?
)
