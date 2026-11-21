package com.ikar.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatAssessmentEngineTest {
    @Test
    fun noSignalsProducesNoRiskIndicators() {
        val result = ThreatAssessmentEngine.assess(emptySet())
        assertEquals(0, result.score)
        assertEquals("Aucun indice", result.level)
    }

    @Test
    fun accessibilityCombinedWithSmsRaisesRisk() {
        val result = ThreatAssessmentEngine.assess(setOf("accessibility", "sms_access"))
        assertTrue(result.score >= 45)
        assertEquals("Important", result.level)
        assertTrue(result.reasons.any { it.contains("accessibilité") })
    }

    @Test
    fun repeatedSignalsAreNotCountedTwice() {
        val result = ThreatAssessmentEngine.assess(setOf("sms_access", "sms_access"))
        assertEquals(18, result.score)
    }

    @Test
    fun unknownSignalsAreIgnored() {
        val result = ThreatAssessmentEngine.assess(setOf("not_a_real_signal"))
        assertEquals(0, result.score)
    }
    @Test
    fun accessibilityNotificationsAndSmsProduceStrongerCorrelation() {
        val result = ThreatAssessmentEngine.assess(
            setOf("accessibility", "notification_listener", "sms_access")
        )
        assertTrue(result.score >= 80)
        assertTrue(result.reasons.any { it.contains("Corrélation renforcée") })
    }

    @Test
    fun accessibilityAndDeviceAdminAreCorrelated() {
        val result = ThreatAssessmentEngine.assess(setOf("accessibility", "device_admin"))
        assertTrue(result.score >= 55)
        assertTrue(result.reasons.any { it.contains("administrateur") })
    }

}
