package com.ikar.assistant.security

import com.ikar.assistant.data.MemoryDao

/**
 * Plug-in contract for real app operations. A handler must implement a true
 * compensating operation before declaring an action reversible.
 */
interface PendingActionHandler {
    val actionId: String
    suspend fun execute(action: PendingActionEntity): String
    suspend fun undo(action: PendingActionEntity): String
}

object PendingActionCoordinator {
    sealed class Result {
        data class Success(val message: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    suspend fun confirmAndExecute(
        dao: MemoryDao,
        actionId: String,
        explicitUserConfirmation: Boolean,
        handler: PendingActionHandler
    ): Result {
        val pending = dao.getPendingAction(actionId)
            ?: return Result.Rejected("Demande introuvable.")
        if (handler.actionId != pending.actionId) return Result.Rejected("Le gestionnaire ne correspond pas à cette action.")
        val confirmed = try {
            PendingActionPolicy.confirm(pending, explicitUserConfirmation)
        } catch (error: Exception) {
            return Result.Rejected(error.message ?: "Confirmation refusée.")
        }
        dao.savePendingAction(confirmed)
        ActionAudit.record(dao, confirmed.actionId, "confirmed", "Confirmation explicite reçue depuis l'interface.")
        return try {
            val outcome = handler.execute(confirmed)
            val completed = PendingActionPolicy.complete(confirmed, outcome)
            dao.savePendingAction(completed)
            ActionAudit.record(dao, completed.actionId, "completed", outcome)
            Result.Success(outcome)
        } catch (error: Exception) {
            ActionAudit.record(dao, confirmed.actionId, "execution_failed", error.javaClass.simpleName)
            Result.Rejected("Échec de l'action. Vérifie son état avant de réessayer.")
        }
    }

    suspend fun undoCompleted(
        dao: MemoryDao,
        actionId: String,
        handler: PendingActionHandler
    ): Result {
        val completed = dao.getPendingAction(actionId)
            ?: return Result.Rejected("Action introuvable.")
        if (handler.actionId != completed.actionId) return Result.Rejected("Le gestionnaire ne correspond pas à cette action.")
        if (!completed.reversible) return Result.Rejected("Cette action n'a pas de procédure d'annulation garantie.")
        return try {
            val undoResult = handler.undo(completed)
            val undone = PendingActionPolicy.markUndone(completed)
            dao.savePendingAction(undone)
            ActionAudit.record(dao, completed.actionId, "undone", undoResult)
            Result.Success(undoResult)
        } catch (error: Exception) {
            ActionAudit.record(dao, completed.actionId, "undo_failed", error.javaClass.simpleName)
            Result.Rejected("L'annulation a échoué. L'état réel doit être vérifié.")
        }
    }
}
