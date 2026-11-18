package com.ikar.assistant.security

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_actions",
    indices = [Index(value = ["status", "expiresAt"])]
)
data class PendingActionEntity(
    @PrimaryKey val id: String,
    val actionId: String,
    val title: String,
    val explanation: String,
    val risk: String,
    val reversible: Boolean,
    val undoDescription: String?,
    val status: String = STATUS_PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = createdAt + DEFAULT_TTL_MS,
    val confirmedAt: Long? = null,
    val completedAt: Long? = null,
    val resultSummary: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_UNDONE = "UNDONE"
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    }
}
