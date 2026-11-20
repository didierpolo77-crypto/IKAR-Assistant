package com.ikar.assistant.ai

import com.ikar.assistant.data.MemoryEntry
import java.text.Normalizer
import java.util.Locale

/** Offline assistant: deterministic safety reasoning plus local memory retrieval. */
object OfflineChatEngine {
    fun answer(question: String, memories: List<MemoryEntry>): String {
        val normalized = normalize(question.trim())
        if (normalized.isBlank()) return "Écris une question pour commencer."

        if (ConversationContextBuilder.isFollowUp(question)) {
            val recent = ConversationContextBuilder.recentMessages(question, memories)
            if (recent.isNotEmpty()) {
                val transcript = recent.joinToString("\\n\\n") { entry ->
                    val speaker = if (entry.type == "USER_MESSAGE") "Toi" else "IKAR"
                    "$speaker : ${entry.content.trim().take(500)}"
                }
                return "Je reprends le fil à partir des messages récents enregistrés localement :\\n\\n" +
                    transcript +
                    "\\n\\nJe m'appuie sur ces messages tels qu'ils ont été enregistrés ; ils ne constituent pas une vérification indépendante des faits. " +
                    "Dis-moi quelle partie tu veux approfondir ou quelle étape je dois poursuivre."
            }
        }

        IkarDecisionEngine.evaluate(question)?.let { decision ->
            return "Analyse IKAR — ${decision.topic} (${decision.confidence})\n\n${decision.response}\n\nJe me base sur les mots de ta demande, pas sur une inspection automatique de ton téléphone."
        }

        IkarAgentCore.respond(question)?.let { reply ->
            return "IKAR • ${reply.intent} — confiance ${reply.confidence}\n\n${reply.message}" +
                if (reply.requiresUserChoice) "\n\nJe n'ai encore exécuté aucune action." else ""
        }

        if (isGreeting(normalized)) {
            return "Bonjour ! Je suis IKAR. Je peux retrouver tes notes locales et t'aider à évaluer certaines situations de sécurité. Mon raisonnement hors connexion utilise actuellement des règles explicites, pas encore un grand modèle d'IA."
        }
        if (normalized.contains("qui es tu")) {
            return "Je suis IKAR Assistant. Je combine une mémoire locale, la lecture de texte dans les images, des outils de vérification de fichiers et une première couche de décision hors connexion. Je ne suis pas encore un modèle conversationnel général comme JARVIS."
        }
        if (normalized.contains("que sais tu de moi") || normalized.contains("ma memoire") || normalized.contains("mes notes")) {
            val saved = memories.filter { it.type == "NOTE" || it.type == "OCR" || it.type == "DOCUMENT" }
            return if (saved.isEmpty()) "Ta mémoire locale ne contient encore aucune note ni aucun texte extrait."
            else "J'ai " + saved.size + " élément(s) dans ta mémoire locale. Pose une question précise pour que je recherche l'information correspondante."
        }

        val stopWords = setOf("comment", "pourquoi", "quand", "quel", "quelle", "quels", "quelles",
            "est", "sont", "les", "des", "une", "un", "dans", "pour", "avec", "sur", "qui", "que",
            "quoi", "mon", "ma", "mes", "ton", "ta", "tes", "du", "de", "la", "le", "et", "ou",
            "je", "tu", "il", "elle", "peux", "peut", "me", "moi", "ce", "cette", "ces")
        val terms = normalized.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stopWords }.toSet()

        if (terms.isEmpty()) return "Je n'ai pas assez de mots-clés pour chercher dans ta mémoire. Reformule avec un ou deux termes importants."

        val ranked = ConversationContextBuilder.relevantMemories(question, memories)

        if (ranked.isEmpty()) return "Je n'ai pas trouvé cette information dans tes notes ou documents locaux. Sans connexion, je ne peux pas encore interroger un moteur de connaissances général. Importe un document ou enregistre une note contenant cette information pour que je puisse la retrouver plus tard."

        val excerpts = ranked.joinToString("\n\n") { entry ->
            val label = when (entry.type) {
                "OCR" -> "Texte extrait d'image"
                "DOCUMENT" -> "Document local"
                else -> "Note personnelle"
            }
            "• " + label + " :\n" + entry.content.trim()
        }
        return "Voici ce que j'ai retrouvé dans ta mémoire locale :\n\n" + excerpts +
            "\n\nCes résultats proviennent uniquement des données enregistrées sur ce téléphone."
    }

    private fun isGreeting(value: String): Boolean =
        value in setOf("salut", "bonjour", "bonsoir", "hello", "coucou", "slt", "bon matin") ||
            value.startsWith("salut ") || value.startsWith("bonjour ") || value.startsWith("bonsoir ")

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace("’", "'").trim()
}
