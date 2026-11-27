package com.ikar.assistant.ai

import java.net.URI
import java.io.InputStream
import com.ikar.assistant.security.BoundedResponseReader
import com.ikar.assistant.data.MemoryEntry

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
        append(if (status.equals("online", ignoreCase = true)) "Serveur IKAR Vercel accessible." else "État du serveur : $status.")
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
 * HTTPS client for the API Vercel IKAR. The app supplies the access token at runtime;
 * no server credential is embedded in the APK or persisted by this client.
 * Call network methods only from Dispatchers.IO.
 */
object IkarOnlineModelGateway {
    private const val BASE_URL = "https://ikar-cloudflare-worker-v4gl.vercel.app"
    private const val ENDPOINT = "$BASE_URL/v1/chat"
    private const val HEALTH_ENDPOINT = "$BASE_URL/health"
    private const val MAX_API_RESPONSE_BYTES = 1_048_576

    private fun readBoundedResponse(stream: InputStream?, sourceName: String): String =
        stream?.use { BoundedResponseReader.readUtf8(it, MAX_API_RESPONSE_BYTES, sourceName) }.orEmpty()

    /** Vérifie un domaine ou une adresse IP via AdGuard, URLhaus et ThreatFox côté serveur.
     * Seul l'indicateur demandé est transmis ; aucun fichier du téléphone n'est envoyé.
     * Appeler depuis Dispatchers.IO.
     */
    fun checkDomain(apiToken: String, domain: String): String {
        require(apiToken.isNotBlank()) { "Ajoute le jeton d'accès IKAR pour vérifier un domaine." }
        require(domain.isNotBlank()) { "Saisis un domaine ou une adresse IP." }
        return postSecurityJson(
            "/v1/security/domain-check",
            apiToken,
            JSONObject().put("domain", domain.trim().take(253))
        )
    }

    /** Résolution DNS AdGuard DoH. Une résolution DNS ne prouve pas qu'un site est sûr. */
    fun resolveDns(apiToken: String, name: String, recordType: String = "A"): String {
        require(apiToken.isNotBlank()) { "Ajoute le jeton d'accès IKAR pour lancer la résolution DNS." }
        require(name.isNotBlank()) { "Saisis un nom de domaine." }
        return postSecurityJson(
            "/api/dns/resolve",
            apiToken,
            JSONObject().put("name", name.trim().take(253)).put("type", recordType.uppercase().take(5))
        )
    }

    private fun postSecurityJson(path: String, apiToken: String, payload: JSONObject): String {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = readBoundedResponse(stream, "API de sécurité IKAR")
            if (status !in 200..299) {
                val error = runCatching { JSONObject(body) }.getOrNull()
                val detail = error?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: error?.optString("error")?.takeIf { it.isNotBlank() }
                throw IllegalStateException(
                    when (status) {
                        401 -> "Jeton IKAR refusé. Vérifie le jeton dans les réglages."
                        503 -> detail ?: "Le service IKAR ou l'une de ses sources est indisponible."
                        502 -> detail ?: "Une source externe n'a pas répondu correctement."
                        else -> detail ?: "Erreur de l'API IKAR (HTTP $status)."
                    }
                )
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

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
            val body = readBoundedResponse(stream, "diagnostic serveur IKAR")
            if (statusCode !in 200..299) {
                throw IllegalStateException("Le serveur Vercel répond HTTP $statusCode.")
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

    /**
     * Sends only app metadata and local heuristic findings for optional AI review.
     * No APK, file contents, contacts, or personal data are uploaded.
     * Call from Dispatchers.IO.
     */
    fun analyzeSecurity(apiToken: String, finding: com.ikar.assistant.security.AppRisk): String {
        require(apiToken.isNotBlank()) { "Ajoute le jeton d'accès Vercel pour utiliser l'analyse IA." }
        val evidence = JSONObject()
            .put("app_label", finding.label.take(120))
            .put("package_name", finding.packageName.take(255))
            .put("local_score", finding.score.coerceIn(0, 100))
            .put("local_level", finding.severity.take(30))
            .put("signals", JSONArray(finding.reasons.take(20)))
            .put("reasons", JSONArray(finding.reasons.take(20)))
        val connection = (URL("https://ikar-cloudflare-worker-v4gl.vercel.app/v1/security/analyze")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 65_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(evidence.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = readBoundedResponse(stream, "analyse de sécurité IKAR")
            val response = runCatching { JSONObject(responseText) }.getOrNull()
            if (status !in 200..299) {
                val reason = when (status) {
                    401 -> "Jeton IKAR refusé. Vérifie la configuration sans partager le jeton."
                    503 -> response?.optString("message")?.ifBlank { null }
                        ?: "Le moteur IA de sécurité est indisponible ou limité."
                    502 -> response?.optString("message")?.ifBlank { null }
                        ?: "Le fournisseur IA n'a pas pu analyser les métadonnées."
                    else -> "Erreur du serveur de sécurité IKAR (HTTP $status)."
                }
                throw IllegalStateException(reason)
            }
            return response?.optString("reply")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("L'analyse IA a renvoyé une réponse vide.")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Never leave chat unusable when the remote provider rejects a request.
     * The fallback is explicitly identified as local/rule-based, not as an AI reply.
     */
    fun sendMessageWithLocalFallback(
        apiToken: String,
        message: String,
        history: List<ConversationTurn>,
        memories: List<MemoryEntry>
    ): String {
        return try {
            sendMessage(apiToken, message, history)
        } catch (error: Exception) {
            val reason = error.localizedMessage
                ?.replace(Regex("[\\r\\n]+"), " ")
                ?.take(240)
                ?.takeIf { it.isNotBlank() }
                ?: "Erreur réseau ou fournisseur indisponible."
            val localAnswer = OfflineChatEngine.answer(message, memories)
            "⚠️ IA en ligne indisponible : $reason\n\nJe bascule en réponse locale (règles et mémoire du téléphone, pas un modèle IA général) :\n\n$localAnswer"
        }
    }

    fun sendMessage(
        apiToken: String,
        message: String,
        history: List<ConversationTurn>
    ): String {
        require(apiToken.isNotBlank()) {
            "Ajoute le jeton d'accès Vercel pour utiliser le mode connecté."
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
            val responseText = readBoundedResponse(stream, "chat IKAR")
            val response = runCatching { JSONObject(responseText) }.getOrNull()

            if (status !in 200..299) {
                val error = response?.optString("error").orEmpty()
                val serverMessage = response?.optString("message").orEmpty()
                val reason = when {
                    status == 401 -> "Jeton d'accès IKAR refusé. Vérifie IKAR_API_TOKEN."
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
