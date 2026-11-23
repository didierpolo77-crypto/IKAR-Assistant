package com.ikar.assistant.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ikar.assistant.security.PendingActionEntity
import com.ikar.assistant.security.MalwareBazaarIntelEntity

@Dao
interface MemoryDao {
    @Insert
    suspend fun insertStored(entry: MemoryEntry): Long

    @Transaction
    suspend fun insert(entry: MemoryEntry): Long =
        insertStored(entry.copy(content = MemoryCrypto.encrypt(entry.content)))

    @Query("SELECT * FROM memory_entries ORDER BY createdAt DESC")
    suspend fun getAllStored(): List<MemoryEntry>

    @Transaction
    suspend fun getAll(): List<MemoryEntry> =
        getAllStored().map { it.copy(content = MemoryCrypto.decrypt(it.content)) }

    @Delete
    suspend fun delete(entry: MemoryEntry)

    @Query("DELETE FROM memory_entries")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePendingAction(action: PendingActionEntity)

    @Query("SELECT * FROM pending_actions WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getOpenPendingActions(): List<PendingActionEntity>

    @Query("SELECT * FROM pending_actions WHERE id = :id LIMIT 1")
    suspend fun getPendingAction(id: String): PendingActionEntity?

    @Query("UPDATE pending_actions SET status = 'EXPIRED' WHERE status = 'PENDING' AND expiresAt <= :now")
    suspend fun expirePendingActions(now: Long = System.currentTimeMillis()): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMalwareBazaarIntel(entries: List<MalwareBazaarIntelEntity>)

    @Query("SELECT * FROM malwarebazaar_intel WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findMalwareBazaarIntel(sha256: String): MalwareBazaarIntelEntity?

    @Query("SELECT COUNT(*) FROM malwarebazaar_intel")
    suspend fun countMalwareBazaarIntel(): Int

    @Query("SELECT * FROM malwarebazaar_intel ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun oldestMalwareBazaarIntel(limit: Int): List<MalwareBazaarIntelEntity>
}
