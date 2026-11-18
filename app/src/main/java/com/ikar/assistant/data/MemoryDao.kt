package com.ikar.assistant.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ikar.assistant.security.PendingActionEntity

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(entry: MemoryEntry): Long

    @Query("SELECT * FROM memory_entries ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryEntry>

    @Delete
    suspend fun delete(entry: MemoryEntry)

    @Query("DELETE FROM memory_entries")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePendingAction(action: PendingActionEntity)

    @Query("SELECT * FROM pending_actions WHERE status IN ('PENDING', 'CONFIRMED') ORDER BY createdAt DESC")
    suspend fun getOpenPendingActions(): List<PendingActionEntity>

    @Query("SELECT * FROM pending_actions WHERE id = :id LIMIT 1")
    suspend fun getPendingAction(id: String): PendingActionEntity?

    @Query("UPDATE pending_actions SET status = 'EXPIRED' WHERE status = 'PENDING' AND expiresAt <= :now")
    suspend fun expirePendingActions(now: Long = System.currentTimeMillis()): Int
}
