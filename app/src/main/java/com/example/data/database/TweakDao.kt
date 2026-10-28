package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TweakDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: TweakHistoryEntity): Long

    @Query("SELECT * FROM tweak_history ORDER BY timestamp DESC LIMIT 100")
    fun getAllHistory(): Flow<List<TweakHistoryEntity>>

    @Query("DELETE FROM tweak_history")
    suspend fun clearHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBackup(backup: TweakBackupEntity)

    @Query("SELECT * FROM tweak_backups WHERE tweakKey = :key LIMIT 1")
    suspend fun getBackup(key: String): TweakBackupEntity?

    @Query("SELECT * FROM tweak_backups")
    suspend fun getAllBackups(): List<TweakBackupEntity>

    @Query("DELETE FROM tweak_backups WHERE tweakKey = :key")
    suspend fun removeBackup(key: String)
}
