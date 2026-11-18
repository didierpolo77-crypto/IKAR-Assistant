package com.ikar.assistant.security

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

data class FileScanReport(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val isApk: Boolean,
    val packageName: String?,
    val requestedSensitivePermissions: List<String>,
    val indicators: List<String>,
    val verdict: String,
    val updateAssessment: String? = null
)

/**
 * Offline, read-only first-pass scanner for a file explicitly selected by the user.
 * A hash is an identifier, not a malware verdict. Heuristics are indicators only.
 */
object FileScanner {
    private const val MAX_FILE_BYTES = 100L * 1024L * 1024L

    private val sensitivePermissions = mapOf(
        "android.permission.RECORD_AUDIO" to "Microphone",
        "android.permission.CAMERA" to "Caméra",
        "android.permission.ACCESS_FINE_LOCATION" to "Localisation précise",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Localisation en arrière-plan",
        "android.permission.READ_SMS" to "Lecture des SMS",
        "android.permission.RECEIVE_SMS" to "Réception des SMS",
        "android.permission.SEND_SMS" to "Envoi de SMS",
        "android.permission.READ_CALL_LOG" to "Journal d'appels",
        "android.permission.READ_CONTACTS" to "Contacts",
        "android.permission.READ_PHONE_STATE" to "État du téléphone"
    )

    fun scan(context: Context, uri: Uri): FileScanReport {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "fichier sélectionné"

        val temp = File.createTempFile("ikar_scan_", ".bin", context.cacheDir)
        try {
            var total = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FILE_BYTES) { "Fichier trop volumineux : limite de 100 Mo." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalStateException("Impossible de lire ce fichier.")

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val indicators = mutableListOf<String>()
            val apkByName = name.endsWith(".apk", ignoreCase = true)
            val header = ByteArray(4)
            temp.inputStream().use { it.read(header) }
            val isZip = header.size == 4 && header[0] == 0x50.toByte() &&
                header[1] == 0x4b.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            val isApk = apkByName && isZip

            if (apkByName && !isZip) indicators += "Extension APK mais signature ZIP absente : fichier à vérifier."
            if (total == 0L) indicators += "Fichier vide."

            var packageName: String? = null
            val sensitive = mutableListOf<String>()
            if (isApk) {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageArchiveInfo(
                    temp.absolutePath,
                    PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
                )
                if (info == null) {
                    indicators += "Android n'a pas pu lire le manifeste de cet APK."
                } else {
                    packageName = info.packageName
                    info.requestedPermissions.orEmpty().forEach { permission ->
                        sensitivePermissions[permission]?.let { sensitive += it }
                    }
                    if (sensitive.isNotEmpty()) {
                        indicators += "Permissions sensibles déclarées : ${sensitive.distinct().joinToString(", ")}."
                    }
                    val flags = info.applicationInfo?.flags ?: 0
                    if ((flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        indicators += "APK marqué comme déboguable."
                    }
                    if ((flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY) != 0) {
                        indicators += "APK marqué comme réservé aux tests."
                    }
                }
            } else if (apkByName) {
                indicators += "Le fichier n'a pas été reconnu comme une archive APK valide."
            }

            val verdict = when {
                indicators.any { it.contains("signature ZIP absente") || it.contains("n'a pas pu lire") || it.contains("pas été reconnu") } ->
                    "Fichier invalide ou non vérifiable"
                indicators.isNotEmpty() -> "Vérification recommandée"
                else -> "Aucun indicateur suspect détecté par les règles locales"
            }
            val updateAssessment = if (isApk && packageName != null) {
                assessUpdateCompatibility(context, temp.absolutePath, packageName)
            } else null

            return FileScanReport(
                fileName = name,
                sizeBytes = total,
                sha256 = hash,
                isApk = isApk,
                packageName = packageName,
                requestedSensitivePermissions = sensitive.distinct(),
                indicators = indicators,
                verdict = verdict,
                updateAssessment = updateAssessment
            )
        } finally {
            temp.delete()
        }
    }
    private fun assessUpdateCompatibility(context: Context, apkPath: String, candidatePackage: String): String {
        if (candidatePackage != context.packageName) {
            return "Cet APK n'est pas une mise à jour de cette installation IKAR (identifiant de paquet différent)."
        }
        return try {
            val pm = context.packageManager
            val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val candidate = pm.getPackageArchiveInfo(apkPath, flags)
                ?: return "Impossible de lire le certificat de signature de cet APK."
            @Suppress("DEPRECATION")
            val installed = pm.getPackageInfo(context.packageName, flags)

            fun digests(info: android.content.pm.PackageInfo): Set<String> {
                val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    info.signingInfo?.apkContentsSigners?.toList().orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures?.toList().orEmpty()
                }
                return signatures.map { signature ->
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                }.toSet()
            }

            if (digests(candidate).isEmpty() || digests(installed).isEmpty()) {
                "Signature absente ou illisible : n'installe pas cet APK comme mise à jour."
            } else if (digests(candidate) != digests(installed)) {
                "ATTENTION : certificat différent de celui de l'application installée. Android devrait refuser la mise à jour."
            } else {
                val candidateVersion = if (android.os.Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
                val installedVersion = if (android.os.Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
                if (candidateVersion > installedVersion) {
                    "Signature identique et version plus récente : candidat compatible sur le plan de la signature. Android doit encore confirmer l'installation."
                } else {
                    "Signature identique, mais la version APK n'est pas plus récente que celle installée."
                }
            }
        } catch (error: Exception) {
            "Vérification de signature impossible : " + error.javaClass.simpleName + "."
        }
    }

}
