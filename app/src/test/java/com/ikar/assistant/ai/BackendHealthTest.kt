package com.ikar.assistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendHealthTest {
    @Test
    fun healthSummaryShowsConfigurationWithoutExposingSecrets() {
        val summary = BackendHealth(
            status = "online",
            authenticationConfigured = true,
            modelConfigured = true,
            providerTokenConfigured = true
        ).summary()

        assertTrue(summary.contains("Serveur Render accessible"))
        assertTrue(summary.contains("jeton IKAR présent"))
        assertTrue(summary.contains("jeton fournisseur présent"))
        assertFalse(summary.contains("Bearer"))
        assertFalse(summary.contains("hf_"))
    }

    @Test
    fun healthSummaryReportsMissingConfiguration() {
        val summary = BackendHealth(
            status = "online",
            authenticationConfigured = false,
            modelConfigured = true,
            providerTokenConfigured = false
        ).summary()

        assertTrue(summary.contains("jeton IKAR manquant"))
        assertTrue(summary.contains("jeton fournisseur manquant"))
    }
}
