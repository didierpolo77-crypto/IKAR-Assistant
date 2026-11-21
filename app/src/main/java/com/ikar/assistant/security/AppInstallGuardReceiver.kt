package com.ikar.assistant.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Event-driven guard for newly installed or updated packages.
 * It correlates package permissions with Android-visible special access.
 * Heuristic only: it cannot inspect app runtime behavior or prove infection.
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
        SharedStorageBehaviorMonitor.recordPackageInstall(context)

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
        val requested = info.requestedPermissions.orEmpty()
        val permissionFlags = info.requestedPermissionsFlags ?: intArrayOf()
        val granted = requested.filterIndexed { index, _ ->
            ((permissionFlags.getOrNull(index) ?: 0) and PackageManager.REQUESTED_PERMISSION_GRANTED) != 0
        }.toSet()
        val signals = mutableSetOf<String>()
        val indicators = mutableListOf<String>()

        if (granted.any { it in setOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.SEND_SMS"
            ) }) {
            signals += "sms_access"
            indicators += "autorisations SMS accordées"
        }
        if ("android.permission.RECORD_AUDIO" in granted) {
            signals += "microphone"
            indicators += "autorisation microphone accordée"
        }
        if ("android.permission.CAMERA" in granted) {
            signals += "camera"
            indicators += "autorisation caméra accordée"
        }
        if ("android.permission.ACCESS_BACKGROUND_LOCATION" in granted) {
            signals += "background_location"
            indicators += "autorisation de localisation en arrière-plan accordée"
        }
        if ("android.permission.READ_CALL_LOG" in granted) {
            signals += "call_log"
            indicators += "autorisation journal d'appels accordée"
        }
        if ("android.permission.READ_CONTACTS" in granted) {
            signals += "contacts"
            indicators += "autorisation contacts accordée"
        }

        fun packageHasEnabledComponent(setting: String): Boolean =
            Settings.Secure.getString(context.contentResolver, setting).orEmpty()
                .split(':')
                .any { it.substringBefore('/').equals(packageName, ignoreCase = true) }

        if (packageHasEnabledComponent(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)) {
            signals += "accessibility"
            indicators += "service d'accessibilité activé"
        }
        if (packageHasEnabledComponent("enabled_notification_listeners")) {
            signals += "notification_listener"
            indicators += "accès aux notifications activé"
        }
        val activeAdmins = try {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .activeAdmins.orEmpty().any { it.packageName == packageName }
        } catch (_: Exception) { false }
        if (activeAdmins) {
            signals += "device_admin"
            indicators += "administrateur de l'appareil actif"
        }

        val installer = try { pm.getInstallerPackageName(packageName) } catch (_: Exception) { null }
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (installer == null && !isSystem) {
            signals += "unknown_installer"
            indicators += "source d'installation non identifiée"
        }
        if ((appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            signals += "debuggable"
            indicators += "application déboguable"
        }

        val assessment = ThreatAssessmentEngine.assess(signals)
        if (assessment.score < 25) return

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
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val explanation = (indicators + assessment.reasons).distinct().joinToString(" · ")
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("IKAR : application à examiner")
            .setContentText("$label — risque ${assessment.level} (${assessment.score}/100)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$label — $explanation. Ces indices ne prouvent pas une infection. Vérifie l'application et sa provenance."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(packageName.hashCode(), notification) }
    }
}
