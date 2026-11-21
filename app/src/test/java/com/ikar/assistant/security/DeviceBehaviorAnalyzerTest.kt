package com.ikar.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBehaviorAnalyzerTest {
    @Test
    fun noRecentEventsProducesNoSignal() {
        val result = DeviceBehaviorAnalyzer.assess(0, 0)
        assertEquals(0, result.score)
        assertEquals("Aucun signal", result.level)
    }

    @Test
    fun storageBurstAndInstallBurstAreCorrelated() {
        val result = DeviceBehaviorAnalyzer.assess(25, 3)
        assertEquals(75, result.score)
        assertEquals("Élevé", result.level)
        assertTrue(result.reasons.any { it.contains("Corrélation temporelle") })
        assertTrue(result.reasons.any { it.contains("pas d'identifier") || it.contains("identifier") })
    }

    @Test
    fun aSingleBurstIsOnlyAnIndicator() {
        val result = DeviceBehaviorAnalyzer.assess(30, 0)
        assertEquals(30, result.score)
        assertEquals("À examiner", result.level)
    }
}
