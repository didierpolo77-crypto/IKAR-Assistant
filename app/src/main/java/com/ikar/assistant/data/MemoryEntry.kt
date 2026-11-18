package com.ikar.assistant.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_entries",
    indices = [Index(value = ["createdAt"])]
)
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
