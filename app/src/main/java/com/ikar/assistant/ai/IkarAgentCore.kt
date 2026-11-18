package com.ikar.assistant.ai

import com.ikar.assistant.security.ActionSafetyPolicy
import java.text.Normalizer
import java.util.Locale

/**
 * Offline orchestration layer for IKAR.
 *
 * It prepares transparent, task-specific plans but never executes device actions.
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

        if (has(q, "lien suspect", "verifier un lien", "vérifier un lien", "analyser un lien", "phishing", "hameconnage", "hameçonnage")) {
            return AgentReply(
                intent = "Plan de vérification d'un lien",
                confidence = "élevée",
                message = "Voici comment je peux t'aider :\n1. Tu colles le lien dans le vérificateur IKAR, sans l'ouvrir.\n2. IKAR examine localement des indices comme HTTP non chiffré, adresse IP, domaine ressemblant à une marque, raccourcisseur et chemin suspect.\n3. Je t'explique les signaux détectés et leur niveau de prudence.\n4. En cas de doute, n'entre aucun mot de passe ni code et ouvre le site officiel en saisissant toi-même son adresse.\n\nLimite : ces règles ne consultent pas encore Google Safe Browsing et ne peuvent pas garantir qu'un lien est sain.",
                requiresUserChoice = true
            )
        }

        if (has(q, "analyser un apk", "scanner un apk", "verifier un apk", "vérifier un apk", "application apk")) {
            return AgentReply(
                intent = "Plan d'analyse APK",
                confidence = "élevée",
                message = "Procédure proposée :\n1. Sélectionne le fichier APK avec le sélecteur Android.\n2. IKAR calcule son empreinte SHA-256 et lit les informations du paquet accessibles.\n3. Je t'aide à interpréter le nom du paquet, la version, la signature et certaines permissions sensibles.\n4. Si un élément est inhabituel, n'installe pas le fichier avant vérification auprès de l'éditeur officiel.\n\nLimite : sans base de signatures antivirus intégrée, une empreinte seule ne prouve pas qu'un APK est malveillant.",
                requiresUserChoice = true
            )
        }

        if (has(q, "rançongiciel", "ransomware", "fichiers modifies", "fichiers modifiés", "dossier suspect", "surveiller un dossier")) {
            return AgentReply(
                intent = "Plan de surveillance anti-ransomware",
                confidence = "élevée",
                message = "Procédure proposée :\n1. Choisis un dossier avec l'autorisation Android d'accès aux fichiers.\n2. IKAR établit un premier état de référence.\n3. Les contrôles périodiques comparent les fichiers et recherchent des changements ou extensions inhabituels.\n4. Si une anomalie apparaît, évite d'ouvrir les fichiers concernés, déconnecte prudemment le réseau si tu suspectes une attaque et conserve les éléments pour analyse.\n\nLimites : la surveillance est périodique, pas instantanée ; Android peut retarder les contrôles et les changements détectés ne prouvent pas à eux seuls une attaque.",
                requiresUserChoice = true
            )
        }

        if (has(q, "application espion", "espionnage", "spyware", "logiciel espion", "telephone espionne", "téléphone espionné")) {
            return AgentReply(
                intent = "Plan de vérification anti-espionnage",
                confidence = "moyenne",
                message = "Je te propose une vérification prudente :\n1. Lance le contrôle IKAR des applications visibles et des accès sensibles.\n2. Examine les services d'accessibilité, les accès aux notifications et les administrateurs de l'appareil.\n3. Vérifie les applications que tu ne reconnais pas dans les paramètres Android.\n4. Retire une autorisation uniquement si tu comprends son rôle et que tu es certain de ne pas bloquer une fonction utile.\n\nLimites : Android restreint ce qu'une application peut inspecter. Un indicateur suspect n'est pas une preuve d'espionnage et IKAR ne peut pas garantir une détection complète.",
                requiresUserChoice = true
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
            val actionId = when {
                has(q, "supprime", "efface") -> "delete_file"
                has(q, "micro", "camera", "caméra") -> "start_microphone"
                else -> "change_security_setting"
            }
            val policy = ActionSafetyPolicy.assess(actionId)
            return AgentReply(
                intent = "Action sensible demandée",
                confidence = "élevée",
                message = "${policy.explanation} ${policy.undoGuidance.orEmpty()} Je ne lance aucune action depuis cette conversation. Une action disponible devra passer par une interface Android autorisée, avec une confirmation explicite et un résultat inscrit au journal.",
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
