package com.ikar.assistant.security

data class DeviceBehaviorAssessment(
    val score: Int,
    val level: String,
    val reasons: List<String>
)

/**
 * Device-level correlation of events IKAR can observe with Android-granted access.
 * Signals are not attributed to a particular app and are not proof of malware.
 */
object DeviceBehaviorAnalyzer {
    fun assess(mediaChangesLastMinute: Int, installsLastTenMinutes: Int): DeviceBehaviorAssessment {
        val reasons = mutableListOf<String>()
        var score = 0
        val storageBurst = mediaChangesLastMinute >= 25
        val installBurst = installsLastTenMinutes >= 3

        if (storageBurst) {
            score += 30
            reasons += "Rafale de changements dans les médias partagés pendant une minute."
        }
        if (installBurst) {
            score += 20
            reasons += "Plusieurs installations ou mises à jour d'applications en dix minutes."
        }
        if (storageBurst && installBurst) {
            score += 25
            reasons += "Corrélation temporelle : activité inhabituelle sur le stockage et installations rapprochées."
        }

        val bounded = score.coerceIn(0, 100)
        val level = when {
            bounded >= 60 -> "Élevé"
            bounded >= 30 -> "À examiner"
            bounded > 0 -> "Faible"
            else -> "Aucun signal"
        }
        if (reasons.isNotEmpty()) {
            reasons += "Analyse locale globale : ces événements ne permettent pas d'identifier avec certitude l'application responsable."
        }
        return DeviceBehaviorAssessment(bounded, level, reasons)
    }
}
