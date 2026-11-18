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
            var score = 0

            sensitivePermissions.forEach { (permission, rule) ->
                if (permission in requested) {
                    val index = packageInfo?.requestedPermissions?.indexOf(permission) ?: -1
                    val granted = index >= 0 && ((permissionFlags.getOrNull(index) ?: 0)
                        and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    reasons += if (granted) "${rule.label} — autorisation accordée" else rule.label
                    score += rule.weight + if (granted) 1 else 0
                }
            }

            if (app.packageName in accessibilityPackages) {
                reasons += "Service d'accessibilité activé — accès potentiellement étendu"
                score += 5
            }
            if (app.packageName in notificationListenerPackages) {
                reasons += "Accès aux notifications activé"
                score += 3
            }
            if (app.packageName in deviceAdminPackages) {
                reasons += "Administrateur de l'appareil actif"
                score += 4
            }

            val installer = try { pm.getInstallerPackageName(app.packageName) } catch (_: Exception) { null }
            if (installer == null && !isSystem) {
                reasons += "Source d'installation non identifiée — vérifier l'origine"
                score += 1
            }
            if ((app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                reasons += "Application marquée comme déboguable"
                score += 2
            }

            // Keep ordinary system apps out of the list unless a privileged access is enabled.
            val privilegedAccess = app.packageName in accessibilityPackages ||
                app.packageName in notificationListenerPackages ||
                app.packageName in deviceAdminPackages
            if (isSystem && !privilegedAccess) return@mapNotNull null
            if (score < 3) return@mapNotNull null

            val label = try { pm.getApplicationLabel(app).toString() }
                catch (_: Exception) { app.packageName }
            AppRisk(label, app.packageName, score, reasons.distinct(), isSystem)
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
