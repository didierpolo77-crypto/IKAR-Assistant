package com.ikar.assistant.ai

import com.ikar.assistant.data.MemoryEntry

/**
 * Builds a small, transparent context window from recent locally saved chat messages.
 * It does not invent summaries or promote assistant statements to verified facts.
 */
object ConversationContextBuilder {
    private const val MAX_MESSAGES = 8
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun recentMessages(question: String, entries: List<MemoryEntry>): List<MemoryEntry> {
        val now = System.currentTimeMillis()
        return entries
            .asSequence()
            .filter { it.type == "USER_MESSAGE" || it.type == "ASSISTANT_MESSAGE" }
            .filter { now - it.createdAt in 0..MAX_AGE_MS }
            .sortedBy { it.createdAt }
            .toList()
            .let { messages ->
                val last = messages.lastOrNull()
                if (last?.type == "USER_MESSAGE" && last.content.trim() == question.trim()) {
                    messages.dropLast(1)
                } else messages
            }
            .takeLast(MAX_MESSAGES)
    }

    fun isFollowUp(question: String): Boolean {
        val q = question.lowercase()
        return listOf(
            "continue", "poursuis", "vas-y", "va-y", "et ensuite", "ensuite",
            "comme on disait", "reprends", "reviens sur", "qu'en est-il",
            "et pour", "pourquoi", "explique davantage", "développe",
            "tu avais dit", "tu m'avais dit", "notre discussion", "la discussion"
        ).any { q.contains(it) }
    }
}
