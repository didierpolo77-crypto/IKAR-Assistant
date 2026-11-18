package com.ikar.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionPolicyTest {
    private val assessment = ActionSafetyPolicy.assess("show_security_report")

    @Test fun newActionRequiresExplicitConfirmationAndExpires() {
        val action = PendingActionPolicy.create("show_security_report", "Test", "Test action", assessment, now = 1000L)
        assertEquals(PendingActionEntity.STATUS_PENDING, action.status)
        assertTrue(action.expiresAt > 1000L)
        val confirmed = PendingActionPolicy.confirm(action, explicitConfirmation = true, now = 2000L)
        assertEquals(PendingActionEntity.STATUS_CONFIRMED, confirmed.status)
        assertEquals(2000L, confirmed.confirmedAt)
        assertEquals(PendingActionEntity.STATUS_EXPIRED, PendingActionPolicy.expireIfNeeded(action, action.expiresAt).status)
    }

    @Test fun cannotConfirmWithoutConsentOrAfterExpiry() {
        val action = PendingActionPolicy.create("show_security_report", "Test", "Test action", assessment, now = 1000L)
        assertFalse(runCatching { PendingActionPolicy.confirm(action, false, now = 2000L) }.isSuccess)
        assertFalse(runCatching { PendingActionPolicy.confirm(action, true, now = action.expiresAt) }.isSuccess)
    }

    @Test fun destructiveAndUnknownActionsAreNotAutomaticallyAllowed() {
        assertFalse(ActionSafetyPolicy.assess("delete_file").allowedForAutomaticExecution)
        assertFalse(ActionSafetyPolicy.assess("unknown_action").allowedForAutomaticExecution)
        assertFalse(runCatching { PendingActionPolicy.create("unknown_action", "X", "Y", ActionSafetyPolicy.assess("unknown_action")) }.isSuccess)
    }
}
