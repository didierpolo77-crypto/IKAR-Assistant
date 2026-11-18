package com.ikar.assistant.security

import java.util.UUID

/**
 * State machine only: it never performs Android operations.
 * The UI must obtain a fresh, direct user confirmation before calling confirm().
 */
object PendingActionPolicy {
    fun create(
        actionId: String,
        title: String,
        explanation: String,
        assessment: ActionSafetyPolicy.Assessment,
        now: Long = System.currentTimeMillis()
    ): PendingActionEntity {
        require(actionId.isNotBlank() && title.isNotBlank() && explanation.isNotBlank())
        require(assessment.risk != ActionSafetyPolicy.Risk.UNKNOWN) {
            "Une action inconnue ne peut pas être mise en attente pour exécution."
        }
        return PendingActionEntity(
            id = UUID.randomUUID().toString(),
            actionId = actionId,
            title = title.take(120),
            explanation = explanation.take(600),
            risk = assessment.risk.name,
            reversible = assessment.reversible,
            undoDescription = assessment.undoGuidance?.take(300),
            createdAt = now,
            expiresAt = now + PendingActionEntity.DEFAULT_TTL_MS
        )
    }

    fun confirm(
        action: PendingActionEntity,
        explicitConfirmation: Boolean,
        now: Long = System.currentTimeMillis()
    ): PendingActionEntity {
        require(action.status == PendingActionEntity.STATUS_PENDING) { "Cette demande n'est plus en attente." }
        require(now < action.expiresAt) { "Cette demande a expiré. Il faut en créer une nouvelle." }
        require(explicitConfirmation) { "Une confirmation directe de l'utilisateur est obligatoire." }
        return action.copy(status = PendingActionEntity.STATUS_CONFIRMED, confirmedAt = now)
    }

    fun cancel(action: PendingActionEntity): PendingActionEntity {
        require(action.status == PendingActionEntity.STATUS_PENDING) { "Seule une demande en attente peut être annulée." }
        return action.copy(status = PendingActionEntity.STATUS_CANCELLED)
    }

    fun expireIfNeeded(action: PendingActionEntity, now: Long = System.currentTimeMillis()): PendingActionEntity =
        if (action.status == PendingActionEntity.STATUS_PENDING && now >= action.expiresAt)
            action.copy(status = PendingActionEntity.STATUS_EXPIRED)
        else action

    fun complete(action: PendingActionEntity, resultSummary: String): PendingActionEntity {
        require(action.status == PendingActionEntity.STATUS_CONFIRMED) { "L'action doit être confirmée avant son exécution." }
        return action.copy(
            status = PendingActionEntity.STATUS_COMPLETED,
            completedAt = System.currentTimeMillis(),
            resultSummary = resultSummary.take(500)
        )
    }

    fun markUndone(action: PendingActionEntity): PendingActionEntity {
        require(action.status == PendingActionEntity.STATUS_COMPLETED) { "Seule une action terminée peut être annulée." }
        require(action.reversible) { "Cette action n'est pas déclarée réversible." }
        return action.copy(status = PendingActionEntity.STATUS_UNDONE)
    }
}
