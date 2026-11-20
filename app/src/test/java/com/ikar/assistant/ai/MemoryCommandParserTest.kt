package com.ikar.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCommandParserTest {
    @Test
    fun extractsExplicitRememberCommand() {
        assertEquals("Je préfère les réponses courtes", MemoryCommandParser.extract("Mémorise que je préfère les réponses courtes"))
        assertEquals("je travaille sur IKAR", MemoryCommandParser.extract("Souviens-toi que je travaille sur IKAR"))
    }

    @Test
    fun doesNotCaptureOrdinaryQuestions() {
        assertNull(MemoryCommandParser.extract("Est-ce que tu peux mémoriser mes préférences ?"))
        assertNull(MemoryCommandParser.extract("Bonjour IKAR"))
    }

    @Test
    fun comparesFactsWithoutAccentOrPunctuationDifferences() {
        assertTrue(MemoryCommandParser.sameFact("Je préfère le français !", "je prefere le francais"))
    }
}
