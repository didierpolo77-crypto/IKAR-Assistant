package com.ikar.assistant.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Captures a memory only when the user explicitly asks IKAR to remember it.
 * It deliberately does not infer or silently store personal preferences.
 */
object MemoryCommandParser {
    private val prefixes = listOf(
        "souviens-toi que",
        "souviens toi que",
        "retiens que",
        "memorise que",
        "mémorise que",
        "n'oublie pas que",
        "n’oublie pas que",
        "garde en memoire que",
        "garde en mémoire que"
    )

    fun extract(text: String): String? {
        val trimmed = text.trim()
        val prefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return null
        val fact = trimmed.substring(prefix.length).trim().trimStart(':', '-', ' ')
        if (fact.length < 4) return null
        if (fact.length > 600) return null
        return fact
    }

    fun sameFact(first: String, second: String): Boolean = normalize(first) == normalize(second)

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
