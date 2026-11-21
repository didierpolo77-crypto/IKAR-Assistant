package com.ikar.assistant.security

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

data class AppRisk(
    val label: String,
    val packageName: String,
    val score: Int,
    val severity: String,
    val reasons: List<String>,
    val isSystem: Boolean
)

data class SecurityReport(
    val scannedCount: Int,
    val findings: List<AppRisk>,
    val enabledAccessibilityPackages: List<String>,
    val enabledNotificationListenerPackages: List<String>,
    val activeDeviceAdminPackages: List<String>
)

object SpywareScanner {
    private data class PermissionRule(val label: String, val weight: Int)

    private val sensitivePermissions = mapOf(
        Manifest.permission.RECORD_AUDIO to PermissionRule("Microphone demandé", 2),
        Manifest.permission.CAMERA to PermissionRule("Caméra demandée", 2),
        Manifest.permission.ACCESS_FINE_LOCATION to PermissionRule("Localisation précise demandée", 1),
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to PermissionRule("Localisation en arrière-plan demandée", 2),
        Manifest.permission.READ_SMS to PermissionRule("Lecture des SMS demandée", 2),
        Manifest.permission.RECEIVE_SMS to PermissionRule("Réception des SMS demandée", 1),
        Manifest.permission.SEND_SMS to PermissionRule("Envoi de SMS demandé", 2),
        Manifest.permission.READ_CALL_LOG to PermissionRule("Lecture du journal d'appels demandée", 2),
        Manifest.permission.READ_CONTACTS to PermissionRule("Lecture des contacts demandée", 1),
        Manifest.permission.READ_PHONE_STATE to PermissionRule("Accès à l'état du téléphone demandé", 1),
        Manifest.permission.READ_EXTERNAL_STORAGE to PermissionRule("Lecture du stockage partagé demandée", 1),
        Manifest.permission.WRITE_EXTERNAL_STORAGE to PermissionRule("Écriture dans le stockage partagé demandée", 1)
    )

    @Suppress("DEPRECATION")
    fun scan(context: Context): SecurityReport {
        val pm = context.packageManager
        val accessibilityPackages = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').mapNotNull { component ->
            component.substringBefore('/').takeIf { it.isNotBlank() }
        }.toSet()

        val notificationListenerPackages = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty().split(':').mapNotNull { component ->
            component.substringBefore('/').takeIf { it.isNotBlank() }
        }.toSet()

        val deviceAdminPackages = try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.activeAdmins.orEmpty().map { it.packageName }.toSet()
        } catch (_: Exception) { emptySet() }

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val findings = apps.mapNotNull { app ->
            if (app.packageName == context.packageName) return@mapNotNull null
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val packageInfo = try {
                pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            } catch (_: Exception) { null }

            val requested = packageInfo?.requestedPermissions.orEmpty().toSet()
            val permissionFlags = packageInfo?.requestedPermissionsFlags ?: intArrayOf()
            val reasons = mutableListOf<String>()
            val signalIds = mutableSetOf<String>()

            sensitivePermissions.forEach { (permission, rule) ->
                if (permission in requested) {
                    val index = packageInfo?.requestedPermissions?.indexOf(permission) ?: -1
                    val granted = index >= 0 && ((permissionFlags.getOrNull(index) ?: 0)
                        and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    reasons += if (granted) "${rule.label} — autorisation accordée" else rule.label
                    // A permission merely declared by an APK is not evidence of actual access.
                    // Only granted dangerous permissions contribute to the risk score.
                    if (granted) {
                        when (permission) {
                            Manifest.permission.READ_SMS,
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.SEND_SMS -> signalIds += "sms_access"
                            Manifest.permission.RECORD_AUDIO -> signalIds += "microphone"
                            Manifest.permission.CAMERA -> signalIds += "camera"
                            Manifest.permission.ACCESS_FINE_LOCATION -> signalIds += "precise_location"
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> signalIds += "background_location"
                            Manifest.permission.READ_CONTACTS -> signalIds += "contacts"
                            Manifest.permission.READ_PHONE_STATE -> signalIds += "phone_state"
                            Manifest.permission.READ_CALL_LOG -> signalIds += "call_log"
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE -> signalIds += "shared_storage"
                        }
                    }
                }
            }

            if (app.packageName in accessibilityPackages) {
                reasons += "Service d'accessibilité activé — accès potentiellement étendu"
                signalIds += "accessibility"
            }
            if (app.packageName in notificationListenerPackages) {
                reasons += "Accès aux notifications activé"
                signalIds += "notification_listener"
            }
            if (app.packageName in deviceAdminPackages) {
                reasons += "Administrateur de l'appareil actif"
                signalIds += "device_admin"
            }

            val installer = try { pm.getInstallerPackageName(app.packageName) } catch (_: Exception) { null }
            if (installer == null && !isSystem) {
                reasons += "Source d'installation non identifiée — vérifier l'origine"
                signalIds += "unknown_installer"
            }
            if ((app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                reasons += "Application marquée comme déboguable"
                signalIds += "debuggable"
            }

            // Keep ordinary system apps out of the list unless a privileged access is enabled.
            val privilegedAccess = app.packageName in accessibilityPackages ||
                app.packageName in notificationListenerPackages ||
                app.packageName in deviceAdminPackages
            if (isSystem && !privilegedAccess) return@mapNotNull null
            val assessment = ThreatAssessmentEngine.assess(signalIds)
            if (assessment.score < 15) return@mapNotNull null
            reasons += assessment.reasons

            val label = try { pm.getApplicationLabel(app).toString() }
                catch (_: Exception) { app.packageName }
            AppRisk(label, app.packageName, assessment.score, assessment.level, reasons.distinct(), isSystem)
        }.sortedByDescending { it.score }

        return SecurityReport(
            scannedCount = apps.size,
            findings = findings,
            enabledAccessibilityPackages = accessibilityPackages.toList().sorted(),
            enabledNotificationListenerPackages = notificationListenerPackages.toList().sorted(),
            activeDeviceAdminPackages = deviceAdminPackages.toList().sorted()
        )
    }
}
