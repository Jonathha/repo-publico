package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tweak_history")
data class TweakHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val actionName: String,
    val target: String,
    val status: String, // "SUCESSO", "FALHA", "IGNORADO"
    val outputMessage: String,
    val isRestore: Boolean = false
)

@Entity(tableName = "tweak_backups")
data class TweakBackupEntity(
    @PrimaryKey
    val tweakKey: String,
    val savedValue: String,
    val timestamp: Long
)
