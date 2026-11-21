package com.ikar.assistant.diagnostics

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Transparent inventory of Android access currently supported by IKAR.
 * Sensitive capabilities are never enabled silently; Android owns the consent screens.
 */
@Composable
fun SystemAccessCenter() {
    val context = LocalContext.current
    val microphone = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    val notifications = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    val overlay = Settings.canDrawOverlays(context)
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryExemption = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    var listenerEnabled by remember { mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) }
    val notificationPrefs = remember { context.getSharedPreferences("ikar_notification_privacy", Context.MODE_PRIVATE) }
    var seenCount by remember { mutableStateOf(notificationPrefs.getInt("notifications_seen", 0)) }
    var sensitiveCount by remember { mutableStateOf(notificationPrefs.getInt("sensitive_count", 0)) }

    fun openSettings(action: String, packageSpecific: Boolean = false) {
        val intent = if (packageSpecific) {
            Intent(action).apply { data = Uri.parse("package:${context.packageName}") }
        } else Intent(action)
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("Centre d'accès au téléphone", style = MaterialTheme.typography.titleLarge)
            Text(
                "État des accès réellement disponibles pour IKAR. Android garde le contrôle : chaque autorisation sensible doit être accordée dans ses propres réglages.",
                style = MaterialTheme.typography.bodySmall
            )
            AccessLine("Microphone", if (microphone) "Autorisé" else "Non autorisé", "Reconnaissance vocale lorsque tu l'utilises.")
            AccessLine("Notifications", if (notifications) "Autorisé" else "Non autorisé", "Alertes IKAR et événements de sécurité pris en charge.")
            AccessLine("Affichage par-dessus les applications", if (overlay) "Autorisé" else "Non autorisé", "Bulle flottante IKAR, uniquement lorsqu'elle est activée.")
            AccessLine("Optimisation batterie", if (batteryExemption) "IKAR exemptée" else "Gestion Android active", "Android peut limiter les tâches en arrière-plan.")
            AccessLine("Fichiers personnels", "Accès sur sélection", "IKAR ne parcourt que les fichiers et dossiers que tu lui désignes avec le sélecteur Android.")
            AccessLine("Liste des applications", "Visibilité déclarée", "Permet l'audit des applications installées et de leurs informations publiques.")
            AccessLine("Accessibilité et contenu des autres applications", "Non activé", "Aucun service d'accessibilité IKAR n'est installé pour lire ou contrôler les écrans d'autres applications.")
            AccessLine("Notifications des autres applications", if (listenerEnabled) "Autorisé" else "À autoriser", "Analyse locale des alertes reçues. Le texte brut n’est ni enregistré ni envoyé au serveur.")
            AccessLine("Analyse locale", "$seenCount notifications observées · $sensitiveCount sensibles", "Les codes de vérification et alertes financières sont classés sans conserver leur contenu.")
            AccessLine("Données privées des applications", "Inaccessibles", "Android isole les données internes, mots de passe et conversations des autres applications.")
            Text("Confidentialité notifications : IKAR examine le texte uniquement en mémoire pour classer l’alerte. Le contenu, les codes et les aperçus ne sont pas stockés, exportés ou envoyés à l’IA.", style = MaterialTheme.typography.bodySmall)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                    modifier = Modifier.weight(1f)
                ) { Text("Accès notifications") }
                Button(
                    onClick = {
                        listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                        seenCount = notificationPrefs.getInt("notifications_seen", 0)
                        sensitiveCount = notificationPrefs.getInt("sensitive_count", 0)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Actualiser l'état") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                    modifier = Modifier.weight(1f)
                ) { Text("Autorisations IKAR") }
                Button(
                    onClick = { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) },
                    modifier = Modifier.weight(1f)
                ) { Text("Bulle flottante") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS) },
                    modifier = Modifier.weight(1f)
                ) { Text("Notifications") }
                Button(
                    onClick = { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                    modifier = Modifier.weight(1f)
                ) { Text("Batterie") }
            }
            Text(
                "Confidentialité : aucune de ces autorisations ne donne un accès total au téléphone. IKAR n'active rien en secret et ne transmet pas les données système à son serveur dans ce panneau.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AccessLine(title: String, state: String, explanation: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(state, style = MaterialTheme.typography.labelMedium)
        }
        Text(explanation, style = MaterialTheme.typography.bodySmall)
    }
}
