package com.ikar.assistant.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic, offline decision layer.
 * It classifies explicit user questions and returns cautious next steps.
 * It does not inspect the device or prove that a threat is present.
 */
object IkarDecisionEngine {
    data class Decision(
        val topic: String,
        val confidence: String,
        val response: String
    )

    fun evaluate(question: String): Decision? {
        val q = normalize(question)
        if (q.isBlank()) return null

        return when {
            hasAny(q, "ransomware", "rancon", "fichiers bloques", "fichiers chiffres", "fichiers renommes") ->
                Decision("Ransomware", "correspondance forte", "Je vais t'aider à limiter les dégâts. Si tu soupçonnes un ransomware : 1) coupe temporairement le Wi-Fi et les données mobiles ; 2) n'ouvre pas les fichiers touchés et ne branche aucun autre appareil ; 3) ne paie pas et ne supprime pas les preuves ; 4) conserve les noms et messages affichés. IKAR peut comparer les changements dans le dossier que tu as autorisé, mais cette vérification ne prouve pas à elle seule une infection. Veux-tu lancer une analyse du dossier autorisé ?")

            hasAny(q, "phishing", "hameconnage", "faux lien", "lien suspect", "arnaque lien", "mot de passe sur un lien") ->
                Decision("Hameçonnage", "correspondance forte", "Ne saisis aucun mot de passe et n'installe rien depuis ce lien. Vérifie le nom de domaine exact, l'expéditeur et l'adresse du site officiel en la tapant toi-même. Tu peux coller le lien dans le vérificateur IKAR ; son analyse locale repose sur des indices et ne garantit pas qu'un site est sûr.")

            hasAny(q, "espion", "espionnage", "application espion", "micro active", "camera active", "surveillance") ->
                Decision("Vie privée", "correspondance forte", "Je te conseille de vérifier les autorisations Microphone, Caméra, Localisation, Accessibilité et Accès aux notifications dans les paramètres Android. Désactive celles qui ne sont pas nécessaires et désinstalle uniquement les applications que tu reconnais comme inutiles. IKAR peut signaler des indices, mais une autorisation sensible ne prouve pas qu'une application t'espionne.")

            hasAny(q, "virus", "malware", "logiciel malveillant", "application dangereuse", "apk suspect") ->
                Decision("Logiciel malveillant", "correspondance forte", "N'ouvre pas le fichier ou l'application suspecte. Lance une analyse locale du fichier APK ou du document avec IKAR, puis vérifie aussi Google Play Protect. Le contrôle actuel utilise des empreintes et des indicateurs simples ; sans signatures antivirus à jour, un résultat rassurant ne garantit pas l'absence de malware.")

            hasAny(q, "mode urgence", "urgence", "telephone compromis", "telephone pirate", "compromis") ->
                Decision("Mode urgence", "correspondance moyenne", "Je peux activer les protections propres à IKAR, comme masquer son contenu dans les captures et arrêter sa bulle flottante. Cela ne verrouille pas tout Android et ne coupe pas les fonctions des autres applications. Pour une suspicion sérieuse, déconnecte le réseau, vérifie les applications et change tes mots de passe depuis un appareil de confiance.")

            hasAny(q, "que peux tu faire", "tes capacites", "comment tu reagis", "comment tu te proteges", "comment tu fonctionnes") ->
                Decision("Capacités IKAR", "correspondance forte", "Je fonctionne en plusieurs étapes : je reçois les informations que tu autorises, je les analyse avec mes outils locaux, j'estime les risques, puis je propose une action. Je distingue une détection confirmée d'un simple indice. Pour l'instant, mon raisonnement hors connexion est fondé sur des règles et ta mémoire ; je ne suis pas encore un modèle d'IA général comme JARVIS.")

            else -> null
        }
    }

    private fun hasAny(value: String, vararg terms: String): Boolean =
        terms.any { value.contains(normalize(it)) }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("’", "'")
            .trim()
}
