package com.ikar.assistant.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ikar.assistant.data.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PendingActionCenter(memoryDao: MemoryDao) {
    val scope = rememberCoroutineScope()
    var actions by remember { mutableStateOf<List<PendingActionEntity>>(emptyList()) }
    var message by remember { mutableStateOf("Aucune demande en attente.") }
    suspend fun refresh() {
        actions = withContext(Dispatchers.IO) { memoryDao.expirePendingActions(); memoryDao.getOpenPendingActions() }
    }
    LaunchedEffect(Unit) { refresh() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Actions en attente et confirmations", style = MaterialTheme.typography.titleMedium)
            Text("Les demandes expirent après 5 minutes. Cette démonstration valide la confirmation sans exécuter une action système.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                scope.launch {
                    val assessment = ActionSafetyPolicy.assess("show_security_report")
                    val pending = PendingActionPolicy.create("show_security_report", "Démonstration de confirmation", "Vérifier le mécanisme de confirmation IKAR. Aucun accès ni changement système.", assessment)
                    withContext(Dispatchers.IO) { memoryDao.savePendingAction(pending); ActionAudit.record(memoryDao, pending.actionId, "pending", "Demande de démonstration créée; aucune action système.") }
                    message = "Demande créée. Lis les détails puis confirme ou annule."
                    refresh()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Créer une demande de démonstration") }
            actions.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.explanation, style = MaterialTheme.typography.bodySmall)
                        Text("Risque : ${item.risk} · État : ${item.status}", style = MaterialTheme.typography.labelSmall)
                        Text("Expire : ${java.text.DateFormat.getTimeInstance().format(java.util.Date(item.expiresAt))}", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.status == PendingActionEntity.STATUS_PENDING) {
                                Button(onClick = {
                                    scope.launch {
                                        runCatching {
                                            val confirmed = PendingActionPolicy.confirm(item, explicitConfirmation = true)
                                            withContext(Dispatchers.IO) { memoryDao.savePendingAction(confirmed); ActionAudit.record(memoryDao, item.actionId, "confirmed_not_executed", "Confirmation explicite enregistrée; aucune action système lancée.") }
                                            message = "Confirmation enregistrée. Aucune action système n’a été exécutée."
                                        }.onFailure { message = it.message ?: "Confirmation refusée." }
                                        refresh()
                                    }
                                }) { Text("Confirmer") }
                                Button(onClick = {
                                    scope.launch {
                                        val cancelled = PendingActionPolicy.cancel(item)
                                        withContext(Dispatchers.IO) { memoryDao.savePendingAction(cancelled); ActionAudit.record(memoryDao, item.actionId, "cancelled", "Demande annulée avant exécution.") }
                                        message = "Demande annulée."
                                        refresh()
                                    }
                                }) { Text("Annuler") }
                            } else Text("Confirmation enregistrée, sans exécution.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            Text("L’annulation réelle doit être fournie par chaque action concrète. Une action irréversible ne peut pas être annulée par le logiciel.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
