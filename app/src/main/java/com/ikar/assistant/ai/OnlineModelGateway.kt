package com.ikar.assistant.ai

import java.net.URI

/**
 * Contract for a future HTTPS backend. No provider key or model endpoint is
 * bundled in the APK. Until a backend is configured, online mode stays off.
 */
data class OnlineModelConfig(
    val endpoint: String?,
    val enabled: Boolean = false
) {
    fun validatedEndpoint(): URI? {
        if (!enabled) return null
        val raw = endpoint?.trim().orEmpty()
        if (raw.isBlank()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
        return uri
    }
}

data class ConversationTurn(val role: String, val content: String)

data class OnlineModelRequest(
    val conversationId: String,
    val turns: List<ConversationTurn>
)

sealed class OnlineModelResult {
    data class Success(val answer: String) : OnlineModelResult()
    data class Unavailable(val reason: String) : OnlineModelResult()
}

interface OnlineModelGateway {
    suspend fun generate(request: OnlineModelRequest): OnlineModelResult
}
