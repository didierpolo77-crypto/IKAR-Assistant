package com.ikar.assistant.security

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import java.util.Locale

data class AppRisk(
    val label: String,
    val packageName: String,
    val score: Int,
    val reasons: List<String>,
    val isSystem: Boolean
)

data class SecurityReport(
    val scannedCount: Int,
    val findings: List<AppRisk>,
    val enabledAccessibilityPackages: List<String>
)

object SpywareScanner {
    private val sensitivePermissions = mapOf(
        "android.permission.RECORD_AUDIO" to "Accès au microphone",
        "android.permission.CAMERA" to "Accès à la caméra",
        "android.permission.ACCESS_FINE_LOCATION" to "Localisation précise",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Localisation en arrière-plan",
        "android.permission.READ_SMS" to "Lecture des SMS",
        "android.permission.RECEIVE_SMS" to "Réception des SMS",
        "android.permission.SEND_SMS" to "Envoi de SMS",
        "android.permission.READ_CALL_LOG" to "Lecture du journal d'appels",
        "android.permission.READ_CONTACTS" to "Lecture des contacts",
        "android.permission.READ_PHONE_STATE" to "Informations téléphoniques"
    )

    @Suppress("DEPRECATION")
    fun scan(context: Context): SecurityReport {
        val pm = context.packageManager
        val accessibility = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').mapNotNull { component ->
            component.substringBefore('/').takeIf { it.isNotBlank() }
        }.toSet()

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val findings = apps.mapNotNull { app ->
            if (app.packageName == context.packageName) return@mapNotNull null
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val requested = try {
                val info = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                info.requestedPermissions?.toSet().orEmpty()
            } catch (_: Exception) { emptySet() }

            val reasons = mutableListOf<String>()
            var score = 0
            sensitivePermissions.forEach { (permission, description) ->
                if (permission in requested) {
                    reasons += description
                    score += when (permission) {
                        "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
                        "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.SEND_SMS",
                        "android.permission.READ_SMS", "android.permission.READ_CALL_LOG" -> 2
                        else -> 1
                    }
                }
            }
            if (app.packageName in accessibility) {
                reasons += "Service d'accessibilité activé : vérification conseillée"
                score += 4
            }
            // System applications are reported only when a high-risk accessibility service is enabled.
            if (isSystem && app.packageName !in accessibility) return@mapNotNull null
            if (score < 3) return@mapNotNull null

            val label = try { pm.getApplicationLabel(app).toString() }
                catch (_: Exception) { app.packageName }
            AppRisk(label, app.packageName, score, reasons.distinct(), isSystem)
        }.sortedByDescending { it.score }

        return SecurityReport(apps.size, findings, accessibility.toList())
    }
}
