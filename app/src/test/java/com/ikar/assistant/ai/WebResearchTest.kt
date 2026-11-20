package com.ikar.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebResearchTest {
    @Test
    fun removesExplicitResearchPrefix() {
        assertEquals("énergie solaire", WebResearch.cleanQuery("Recherche sur Internet : énergie solaire"))
        assertEquals("intelligence artificielle", WebResearch.cleanQuery("Cherche sur le web : intelligence artificielle"))
    }

    @Test
    fun preservesPlainTopic() {
        assertEquals("climat au Togo", WebResearch.cleanQuery("climat au Togo"))
    }

    @Test
    fun doesNotReturnBlankQuery() {
        assertTrue(WebResearch.cleanQuery("Recherche sur Internet :").isNotBlank())
    }
}
