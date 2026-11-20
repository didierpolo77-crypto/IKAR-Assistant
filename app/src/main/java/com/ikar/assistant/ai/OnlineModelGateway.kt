package com.ikar.assistant.ai

import java.net.URI

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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


data class BackendHealth(
    val status: String,
    val authenticationConfigured: Boolean,
    val modelConfigured: Boolean,
    val providerTokenConfigured: Boolean
) {
    fun summary(): String = buildString {
        append(if (status.equals("online", ignoreCase = true)) "Serveur Render accessible." else "État du serveur : $status.")
        append("\nConfiguration : ")
        append(if (authenticationConfigured) "jeton IKAR présent" else "jeton IKAR manquant")
        append(" · ")
        append(if (modelConfigured) "modèle défini" else "modèle manquant")
        append(" · ")
        append(if (providerTokenConfigured) "jeton fournisseur présent" else "jeton fournisseur manquant")
        append(".\nCe contrôle confirme l'accès au serveur et la présence des réglages ; il ne garantit pas que Hugging Face acceptera la prochaine génération.")
    }
}

/**
 * HTTPS client for the Render API. The app supplies the access token at runtime;
 * no server credential is embedded in the APK or persisted by this client.
 * Call network methods only from Dispatchers.IO.
 */
object RenderOnlineModelGateway {
    private const val ENDPOINT = "https://ikar-cloudflare-worker-1.onrender.com/v1/chat"
    private const val HEALTH_ENDPOINT = "https://ikar-cloudflare-worker-1.onrender.com/health"

    fun checkHealth(): BackendHealth {
        val connection = (URL(HEALTH_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw IllegalStateException("Le serveur Render répond HTTP $statusCode.")
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("La réponse de santé du serveur est illisible.")
            }
            return BackendHealth(
                status = json.optString("status", "inconnu"),
                authenticationConfigured = json.optBoolean("authentication_configured", false),
                modelConfigured = json.optBoolean("model_configured", false),
                providerTokenConfigured = json.optBoolean("hf_token_configured", false)
            )
        } finally {
            connection.disconnect()
        }
    }

    fun sendMessage(
        apiToken: String,
        message: String,
        history: List<ConversationTurn>
    ): String {
        require(apiToken.isNotBlank()) {
            "Ajoute le jeton d'accès Render pour utiliser le mode connecté."
        }

        val historyJson = JSONArray()
        history.takeLast(4).forEach { turn ->
            if ((turn.role == "user" || turn.role == "assistant") && turn.content.isNotBlank()) {
                historyJson.put(
                    JSONObject()
                        .put("role", turn.role)
                        .put("content", turn.content.take(1000))
                )
            }
        }
        val payload = JSONObject()
            .put("message", message)
            .put("history", historyJson)
            .toString()

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(responseText) }.getOrNull()

            if (status !in 200..299) {
                val error = response?.optString("error").orEmpty()
                val serverMessage = response?.optString("message").orEmpty()
                val reason = when {
                    status == 401 -> "Jeton d'accès Render refusé. Vérifie IKAR_API_TOKEN."
                    status == 503 && error == "ai_rate_limited" ->
                        "Le fournisseur IA limite temporairement les demandes. Réessaie plus tard."
                    status == 503 -> serverMessage.ifBlank { "Le service IA n'est pas disponible pour le moment." }
                    status == 502 -> serverMessage.ifBlank {
                        "Hugging Face n'a pas pu traiter la demande. Vérifie le modèle et les crédits."
                    }
                    else -> "Erreur du serveur IKAR (HTTP $status)."
                }
                throw IllegalStateException(reason)
            }

            return response?.optString("reply")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Le serveur IKAR a renvoyé une réponse vide.")
        } finally {
            connection.disconnect()
        }
    }
}
