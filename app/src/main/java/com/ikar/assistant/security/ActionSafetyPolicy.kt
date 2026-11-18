package com.ikar.assistant.security

/**
 * Central policy for proposed assistant actions. It evaluates plans only;
 * callers must still execute through Android's permission and consent flows.
 */
object ActionSafetyPolicy {
    enum class Risk { READ_ONLY, LOW, SENSITIVE, DESTRUCTIVE, UNKNOWN }

    data class Assessment(
        val risk: Risk,
        val requiresConfirmation: Boolean,
        val allowedForAutomaticExecution: Boolean,
        val reversible: Boolean,
        val explanation: String,
        val undoGuidance: String?
    )

    private val destructive = setOf(
        "delete_file", "delete_app", "clear_data", "factory_reset",
        "send_message", "make_payment", "change_security_setting"
    )
    private val sensitive = setOf(
        "enable_overlay", "start_microphone", "start_screen_capture",
        "read_notifications", "share_private_data", "install_apk"
    )
    private val readOnly = setOf(
        "read_local_note", "analyze_user_selected_file", "check_link",
        "show_security_report"
    )

    fun assess(actionId: String): Assessment {
        val id = actionId.trim().lowercase()
        return when {
            id in readOnly -> Assessment(
                Risk.READ_ONLY, false, false, true,
                "Lecture ou analyse limitée aux données explicitement accessibles.",
                "Aucune modification à annuler."
            )
            id in destructive -> Assessment(
                Risk.DESTRUCTIVE, true, false, false,
                "Action potentiellement destructive : confirmation explicite et aperçu des conséquences obligatoires.",
                "Annulation automatique impossible ou non garantie. Prévoir une sauvegarde avant toute exécution."
            )
            id in sensitive -> Assessment(
                Risk.SENSITIVE, true, false, true,
                "Action sensible : expliquer l'accès demandé et obtenir le consentement dans l'interface Android.",
                "Arrêter le service ou retirer l'autorisation depuis les réglages Android si cela reste possible."
            )
            id == "create_reminder" || id == "save_note" -> Assessment(
                Risk.LOW, true, false, true,
                "Modification locale limitée : présenter le contenu avant l'enregistrement.",
                "Supprimer ou modifier l'élément créé."
            )
            else -> Assessment(
                Risk.UNKNOWN, true, false, false,
                "Action non reconnue : refus par défaut. Aucune exécution automatique.",
                null
            )
        }
    }

    /**
     * Content from OCR, web pages, notifications, files or model responses is
     * data, never an authorization to perform an action.
     */
    fun isTrustedAuthorization(source: String): Boolean =
        source.trim().equals("explicit_user_confirmation", ignoreCase = true)
}
