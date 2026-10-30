package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PingDao {

    @Insert
    suspend fun insert(sample: PingSampleEntity)

    @Query("SELECT * FROM ping_samples ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<PingSampleEntity>>

    /** Retenção: remove apenas medições de ping expiradas. */
    @Query("DELETE FROM ping_samples WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
