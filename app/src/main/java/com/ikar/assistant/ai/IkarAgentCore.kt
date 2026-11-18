package com.ikar.assistant.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Offline orchestration layer for IKAR.
 *
 * It prepares transparent plans but never executes device actions.
 * Sensitive operations must be handled by an explicit, permission-checked UI flow.
 */
object IkarAgentCore {
    data class AgentReply(
        val intent: String,
        val confidence: String,
        val message: String,
        val requiresUserChoice: Boolean = false
    )

    fun respond(question: String): AgentReply? {
        val q = normalize(question)
        if (q.isBlank()) return null

        if (has(q, "que peux tu faire", "tes capacites", "presente toi", "comment tu fonctionnes", "es tu une ia")) {
            return AgentReply(
                intent = "Présentation des capacités",
                confidence = "élevée",
                message = "Je suis IKAR. Je peux dialoguer par texte et voix, retrouver des notes locales, lire le texte d'images que tu choisis, analyser des fichiers sélectionnés, vérifier certains indices dans des liens et auditer un dossier que tu m'as explicitement autorisé. Je peux préparer un plan et expliquer mes recommandations. Je ne peux pas surveiller tout Android, garantir qu'un fichier est sûr ni agir secrètement dans d'autres applications. Hors connexion, mon raisonnement reste spécialisé et fondé sur des règles ; un modèle conversationnel général n'est pas encore intégré."
            )
        }

        if (has(q, "plan d'action", "fais un plan", "organise les etapes", "organise les étapes", "aide moi a organiser", "aide moi à organiser")) {
            return AgentReply(
                intent = "Planification",
                confidence = "moyenne",
                message = "Je peux structurer cette tâche en étapes. Pour éviter d'inventer des détails, j'ai besoin de connaître le résultat que tu veux obtenir et les contraintes importantes. Donne-moi l'objectif précis ; je préparerai un plan, les ressources nécessaires, les risques et les vérifications à faire.",
                requiresUserChoice = true
            )
        }

        if (has(q, "active le mode urgence", "active le mode d urgence", "supprime cette application", "supprime le fichier", "efface mes fichiers", "bloque cette application", "desactive le micro", "désactive le micro", "desactive la camera", "désactive la caméra")) {
            return AgentReply(
                intent = "Action sensible demandée",
                confidence = "élevée",
                message = "Cette demande peut modifier les réglages ou les données de l'appareil. Je ne l'exécute pas automatiquement. Je peux d'abord expliquer les conséquences et te guider vers le réglage Android approprié. Toute action disponible doit être confirmée par toi et respecter les autorisations du système.",
                requiresUserChoice = true
            )
        }

        if (has(q, "continue", "poursuis", "vas y", "va y", "fais le", "fais-le")) {
            return AgentReply(
                intent = "Confirmation de continuation",
                confidence = "faible",
                message = "Je suis prêt à continuer, mais je dois identifier précisément l'étape à poursuivre. Rappelle-moi brièvement l'objectif ou le nom de la fonction concernée pour éviter d'agir sur la mauvaise tâche.",
                requiresUserChoice = true
            )
        }

        return null
    }

    private fun has(value: String, vararg terms: String): Boolean =
        terms.any { value.contains(normalize(it)) }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("’", "'")
            .trim()
}
