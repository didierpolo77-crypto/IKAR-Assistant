package com.ikar.assistant.security

/**
 * Explainable local correlation engine.
 * It combines Android-visible signals; it is heuristic and never proves infection.
 */
data class ThreatAssessment(
    val score: Int,
    val level: String,
    val reasons: List<String>
)

object ThreatAssessmentEngine {
    private val weights = mapOf(
        "sms_access" to 18,
        "microphone" to 8,
        "camera" to 8,
        "precise_location" to 7,
        "background_location" to 10,
        "contacts" to 5,
        "phone_state" to 5,
        "call_log" to 10,
        "shared_storage" to 3,
        "accessibility" to 22,
        "notification_listener" to 14,
        "device_admin" to 18,
        "unknown_installer" to 5,
        "debuggable" to 6
    )

    fun assess(signalIds: Set<String>): ThreatAssessment {
        val known = signalIds.intersect(weights.keys)
        var score = known.sumOf { weights.getValue(it) }
        val reasons = mutableListOf<String>()

        if ("accessibility" in known && "sms_access" in known) {
            score += 20
            reasons += "Combinaison à risque : accessibilité activée avec accès aux SMS."
        }
        if ("notification_listener" in known && "sms_access" in known) {
            score += 12
            reasons += "Combinaison à risque : lecture des notifications et accès aux SMS."
        }
        if ("accessibility" in known && "notification_listener" in known && "sms_access" in known) {
            score += 18
            reasons += "Corrélation renforcée : accessibilité, notifications et SMS réunis dans la même application."
        }
        if ("accessibility" in known && "device_admin" in known) {
            score += 15
            reasons += "Combinaison sensible : service d'accessibilité et administrateur de l'appareil."
        }
        if ("device_admin" in known && "unknown_installer" in known) {
            score += 8
            reasons += "Combinaison à examiner : administrateur actif et source d'installation inconnue."
        }
        if ("background_location" in known && ("microphone" in known || "camera" in known)) {
            score += 8
            reasons += "Combinaison à examiner : localisation en arrière-plan et capteurs sensibles."
        }
        if ("background_location" in known && "microphone" in known && "camera" in known) {
            score += 10
            reasons += "Corrélation renforcée : localisation en arrière-plan et deux capteurs sensibles."
        }

        val bounded = score.coerceIn(0, 100)
        val level = when {
            bounded >= 70 -> "Élevé"
            bounded >= 45 -> "Important"
            bounded >= 25 -> "Modéré"
            bounded > 0 -> "Faible"
            else -> "Aucun indice"
        }
        if (known.isNotEmpty()) {
            reasons += "Évaluation heuristique locale : ces indices ne constituent pas une preuve d'infection."
        }
        return ThreatAssessment(bounded, level, reasons)
    }
}
