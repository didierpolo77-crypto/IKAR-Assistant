package com.ikar.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineModelConfigTest {
    @Test fun disabledGatewayHasNoEndpoint() {
        assertNull(OnlineModelConfig("https://example.com/chat", enabled = false).validatedEndpoint())
    }

    @Test fun httpIsRejected() {
        assertNull(OnlineModelConfig("http://example.com/chat", enabled = true).validatedEndpoint())
    }

    @Test fun httpsEndpointWithoutEmbeddedCredentialsIsAccepted() {
        assertEquals("example.com", OnlineModelConfig("https://example.com/chat", enabled = true)
            .validatedEndpoint()?.host)
    }

    @Test fun embeddedCredentialsAreRejected() {
        assertNull(OnlineModelConfig("https://user:pass@example.com/chat", enabled = true).validatedEndpoint())
    }
}
