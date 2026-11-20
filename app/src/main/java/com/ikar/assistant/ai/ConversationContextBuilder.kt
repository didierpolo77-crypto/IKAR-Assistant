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

    /** Selects only note/OCR entries matching the current question. This context is local unless the user explicitly enables sharing in the UI. */
    fun relevantMemories(question: String, entries: List<MemoryEntry>, maxItems: Int = 3): List<MemoryEntry> {
        val stopWords = setOf("comment", "pourquoi", "quand", "quel", "quelle", "quels", "quelles", "est", "sont", "les", "des", "une", "un", "dans", "pour", "avec", "sur", "qui", "que", "quoi", "mon", "ma", "mes", "ton", "ta", "tes", "du", "de", "la", "le", "et", "ou", "je", "tu", "il", "elle", "peux", "peut", "me", "moi", "ce", "cette", "ces")
        val terms = normalize(question).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stopWords }.toSet()
        if (terms.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { it.type == "NOTE" || it.type == "OCR" }
            .map { entry ->
                val content = normalize(entry.content)
                val words = content.split(Regex("[^\\p{L}\\p{N}]+")).toSet()
                entry to terms.count { it in words || content.contains(it) }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxItems)
            .map { it.first.copy(content = it.first.content.take(400)) }
            .toList()
    }

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\\\p{Mn}+"), "")
            .replace("’", "'")
            .trim()

}
