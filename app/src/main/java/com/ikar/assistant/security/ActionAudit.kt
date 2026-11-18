package com.ikar.assistant.security

import com.ikar.assistant.data.MemoryDao
import com.ikar.assistant.data.MemoryEntry

/** Writes a minimal, local audit trail. Do not store passwords, tokens or full private payloads. */
object ActionAudit {
    suspend fun record(
        dao: MemoryDao,
        actionId: String,
        status: String,
        detail: String
    ) {
        val safeAction = actionId.take(80).replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val safeStatus = status.take(40).replace(Regex("[^A-Za-z0-9 _-]"), "")
        val safeDetail = detail.replace(Regex("(?i)(password|token|secret|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[masqué]")
            .take(500)
        dao.insert(
            MemoryEntry(
                type = "ACTION_AUDIT",
                content = "action=$safeAction; status=$safeStatus; detail=$safeDetail"
            )
        )
    }
}
