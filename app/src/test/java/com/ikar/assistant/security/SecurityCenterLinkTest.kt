package com.ikar.assistant.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityCenterLinkTest {
    @Test
    fun ordinaryHttpsUrlHasNoLocalHeuristicWarning() {
        val result = SecurityCenter.checkLink("https://example.com/")
        assertFalse(result.suspicious)
    }

    @Test
    fun unencryptedHttpUrlIsFlaggedAsWarningNotMalwareVerdict() {
        val result = SecurityCenter.checkLink("http://example.com/")
        assertTrue(result.suspicious)
        assertTrue(result.reasons.any { it.contains("non chiffrée") })
    }

    @Test
    fun urlWithUserInfoIsFlagged() {
        val result = SecurityCenter.checkLink("https://trusted.example@evil.example/login")
        assertTrue(result.suspicious)
        assertTrue(result.reasons.any { it.contains("informations avant") })
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        val result = SecurityCenter.checkLink("javascript:alert(1)")
        assertTrue(result.suspicious)
        assertTrue(result.reasons.any { it.contains("protocole") })
    }
}
