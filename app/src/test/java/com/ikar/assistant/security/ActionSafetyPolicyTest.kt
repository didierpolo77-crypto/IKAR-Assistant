package com.ikar.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ActionSafetyPolicyTest {
    @Test fun unknownActionsAreDeniedByDefault() {
        val result = ActionSafetyPolicy.assess("run_shell_command")
        assertEquals(ActionSafetyPolicy.Risk.UNKNOWN, result.risk)
        assertTrueFalse(result.allowedForAutomaticExecution)
        assertNull(result.undoGuidance)
    }

    @Test fun destructiveActionsRequireConfirmationAndAreNotAutomatic() {
        val result = ActionSafetyPolicy.assess("delete_file")
        assertEquals(ActionSafetyPolicy.Risk.DESTRUCTIVE, result.risk)
        assertFalse(result.allowedForAutomaticExecution)
        assertFalse(result.reversible)
    }

    @Test fun userSuppliedContentCannotAuthorizeActions() {
        assertFalse(ActionSafetyPolicy.isTrustedAuthorization("web_page"))
        assertFalse(ActionSafetyPolicy.isTrustedAuthorization("ocr"))
        assertFalse(ActionSafetyPolicy.isTrustedAuthorization("model_response"))
    }

    private fun assertTrueFalse(value: Boolean) = assertFalse(value)
}
