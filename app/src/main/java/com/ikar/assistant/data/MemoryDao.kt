package com.ikar.assistant.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

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
}
