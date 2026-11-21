package com.ikar.assistant.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Event-driven guard for newly installed or updated packages.
 * It inspects declared permissions and basic package metadata only;
 * it is a heuristic alert, not a malware verdict or process monitor.
 */
class AppInstallGuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED &&
            intent.action != Intent.ACTION_PACKAGE_REPLACED
        ) return
        if (intent.action == Intent.ACTION_PACKAGE_ADDED &&
            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        ) return

        val packageName = intent.data?.schemeSpecificPart ?: return
        if (packageName == context.packageName) return

        val pm = context.packageManager
        val info = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (_: Exception) {
            return
        }

        val appInfo = info.applicationInfo ?: return
        val requested = info.requestedPermissions.orEmpty().toSet()
        val indicators = mutableListOf<String>()
        var score = 0

        val rules = listOf(
            "android.permission.READ_SMS" to (3 to "lecture des SMS"),
            "android.permission.SEND_SMS" to (3 to "envoi de SMS"),
            "android.permission.RECEIVE_SMS" to (2 to "réception des SMS"),
            "android.permission.READ_CALL_LOG" to (2 to "lecture du journal d'appels"),
            "android.permission.ACCESS_BACKGROUND_LOCATION" to (2 to "localisation en arrière-plan"),
            "android.permission.RECORD_AUDIO" to (1 to "microphone"),
            "android.permission.CAMERA" to (1 to "caméra"),
            "android.permission.READ_CONTACTS" to (1 to "contacts"),
            "android.permission.REQUEST_INSTALL_PACKAGES" to (2 to "installation d'autres applications")
        )
        rules.forEach { (permission, rule) ->
            if (permission in requested) {
                score += rule.first
                indicators += rule.second
            }
        }

        if ((appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            score += 2
            indicators += "application déboguable"
        }
        val installer = try { pm.getInstallerPackageName(packageName) } catch (_: Exception) { null }
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (installer == null && !isSystem) {
            score += 1
            indicators += "source d'installation non identifiée"
        }

        // Only combinations with several meaningful signals trigger an alert.
        if (score < 6) return

        val label = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { packageName }
        val channelId = "ikar_threat_alerts"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Alertes de sécurité IKAR", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alertes lors de l'installation ou de la mise à jour d'applications à examiner"
                }
            )
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val detailsIntent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, packageName.hashCode(), detailsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("IKAR : application à examiner")
            .setContentText("$label présente plusieurs indicateurs sensibles.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$label — ${indicators.distinct().joinToString(", ")}. Ces indices ne prouvent pas une infection. Vérifie l'application et sa provenance."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(packageName.hashCode(), notification) }
    }
}
