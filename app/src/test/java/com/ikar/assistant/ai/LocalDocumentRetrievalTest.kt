package com.ikar.assistant.ai

import com.ikar.assistant.data.MemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentRetrievalTest {
    @Test
    fun findsRelevantPassageInsideImportedDocument() {
        val document = MemoryEntry(
            type = "DOCUMENT",
            content = "[Cours de biologie]\n\nLa photosynthèse permet aux plantes de transformer la lumière en énergie chimique.\n\nLa respiration cellulaire est un autre processus."
        )

        val matches = ConversationContextBuilder.relevantMemories(
            "Comment fonctionne la photosynthèse ?",
            listOf(document)
        )

        assertEquals(1, matches.size)
        assertTrue(matches.first().content.contains("photosynthèse"))

        val offlineAnswer = OfflineChatEngine.answer(
            "Comment fonctionne la photosynthèse ?",
            listOf(document)
        )
        assertTrue(offlineAnswer.contains("Document local"))
        assertTrue(offlineAnswer.contains("photosynthèse"))
    }

    @Test
    fun keepsRelevantTextBeyondFirstHundredCharactersInPlainTextFile() {
        val document = MemoryEntry(
            type = "DOCUMENT",
            content = "La photosynthèse est un processus biologique par lequel les plantes utilisent l'énergie lumineuse pour fabriquer des sucres grâce à la chlorophylle située dans les chloroplastes."
        )

        val matches = ConversationContextBuilder.relevantMemories(
            "Quel est le rôle de la chlorophylle dans la photosynthèse ?",
            listOf(document)
        )

        assertEquals(1, matches.size)
        assertTrue(matches.first().content.contains("chlorophylle"))
        assertTrue(matches.first().content.contains("chloroplastes"))
    }

    @Test
    fun keepsFirstBodyParagraphWhenDocumentHasNoExplicitHeading() {
        val document = MemoryEntry(
            type = "DOCUMENT",
            content = "La photosynthèse transforme la lumière en énergie chimique. Les feuilles contiennent de la chlorophylle."
        )

        val matches = ConversationContextBuilder.relevantMemories(
            "Explique la chlorophylle",
            listOf(document)
        )

        assertTrue(matches.first().content.contains("Les feuilles"))
    }

    @Test
    fun followUpTranscriptUsesActualLineBreaks() {
        val now = System.currentTimeMillis()
        val messages = listOf(
            MemoryEntry(type = "USER_MESSAGE", content = "Nous développons IKAR.", createdAt = now - 2_000),
            MemoryEntry(type = "ASSISTANT_MESSAGE", content = "Nous travaillons sur la mémoire.", createdAt = now - 1_000)
        )

        val answer = OfflineChatEngine.answer("continue", messages)

        assertTrue(answer.contains("Toi : Nous développons IKAR.\n\nIKAR :"))
        assertFalse(answer.contains("\\n"))
    }
}
