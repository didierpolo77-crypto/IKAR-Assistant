package com.ikar.assistant.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Chooses the safest useful processing path for each user request.
 * It selects tools/routes only; it never performs device actions itself.
 */
object IkarOrchestrator {
    enum class Destination {
        REMEMBER_LOCALLY,
        LOCAL_SECURITY_GUIDANCE,
        LOCAL_KNOWLEDGE,
        WEB_RESEARCH,
        ONLINE_MODEL,
        OFFLINE_ASSISTANT
    }

    data class Plan(
        val destination: Destination,
        val label: String,
        val reason: String
    )

    fun plan(
        question: String,
        connectedMode: Boolean,
        shareRelevantMemory: Boolean,
        hasRelevantMemory: Boolean
    ): Plan {
        if (MemoryCommandParser.extract(question) != null) {
            return Plan(
                Destination.REMEMBER_LOCALLY,
                "Mémoire locale",
                "La demande contient une instruction explicite de mémorisation."
            )
        }

        if (IkarDecisionEngine.evaluate(question) != null ||
            IkarAgentCore.respond(question)?.requiresUserChoice == true
        ) {
            return Plan(
                Destination.LOCAL_SECURITY_GUIDANCE,
                "Outils locaux et sécurité",
                "Une règle spécialisée peut traiter cette demande sans transmettre le contenu au serveur."
            )
        }

        val normalized = normalize(question)
        val asksForWebResearch = listOf(
            "cherche sur internet", "recherche sur internet",
            "cherche sur le web", "recherche sur le web",
            "fais une recherche web", "recherche web",
            "trouve des sources sur internet", "cherche des sources",
            "actualites recentes", "derniere actualite", "dernieres actualites"
        ).any { normalized.contains(it) }

        if (asksForWebResearch) {
            return Plan(
                Destination.WEB_RESEARCH,
                "Recherche web (Wikipédia)",
                "La demande sollicite explicitement une recherche Internet ; IKAR consulte des articles de l’encyclopédie Wikipédia en français."
            )
        }

        val asksForLocalSources = listOf(
            "dans ma memoire", "dans mes notes", "dans mes documents",
            "dans mon document", "dans mon cours", "selon le pdf",
            "dans le pdf", "dans le fichier", "dans le document",
            "retrouve dans", "cherche dans mes"
        ).any { normalized.contains(it) }

        if (asksForLocalSources || (hasRelevantMemory && (!connectedMode || !shareRelevantMemory))) {
            return Plan(
                Destination.LOCAL_KNOWLEDGE,
                "Mémoire et documents locaux",
                if (asksForLocalSources) {
                    "La demande cible explicitement les données enregistrées sur ce téléphone."
                } else {
                    "Un contenu local correspond à la question et son partage avec le serveur n'est pas autorisé."
                }
            )
        }

        if (connectedMode) {
            return Plan(
                Destination.ONLINE_MODEL,
                "IA en ligne",
                if (hasRelevantMemory && shareRelevantMemory) {
                    "Le mode connecté et le partage explicite des éléments pertinents sont activés."
                } else {
                    "Aucun outil local spécialisé ne correspond ; le mode connecté est activé."
                }
            )
        }

        return Plan(
            Destination.OFFLINE_ASSISTANT,
            "Assistant hors connexion",
            "Le mode connecté est désactivé ; IKAR utilise ses règles et sa base locale."
        )
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("’", "'")
            .trim()
}
