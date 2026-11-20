package com.ikar.assistant.ai

import com.ikar.assistant.data.MemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentRetrievalTest {
    @Test
    fun findsRelevantPassageInsideImportedDocument() {
        val document = MemoryEntry(
            type = "DOCUMENT",
            content = "[Cours de biologie]\n\\nLa photosynthèse permet aux plantes de transformer la lumière en énergie chimique.\\n\\nLa respiration cellulaire est un autre processus."
        )

        val matches = ConversationContextBuilder.relevantMemories(
            "Comment fonctionne la photosynthèse ?",
            listOf(document)
        )

        assertEquals(1, matches.size)
        assertTrue(matches.first().content.contains("photosynthèse"))
    }
}
