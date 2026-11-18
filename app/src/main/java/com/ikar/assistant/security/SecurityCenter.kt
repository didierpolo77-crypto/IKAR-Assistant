package com.ikar.assistant.security

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

data class FolderRiskReport(
    val inspected: Int,
    val changed: Int,
    val suspiciousNames: List<String>,
    val message: String
)

data class LinkRiskReport(val url: String, val suspicious: Boolean, val reasons: List<String>)

object SecurityCenter {
    private const val PREFS = "ikar_security_center"
    private const val SNAPSHOT = "folder_snapshot"

    fun checkLink(raw: String): LinkRiskReport {
        val value = raw.trim()
        val reasons = mutableListOf<String>()
        val parsed = runCatching { URI(value) }.getOrNull()
        if (parsed == null || parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
            reasons += "Adresse web invalide ou protocole non sécurisé."
        } else {
            val host = parsed.host.lowercase(Locale.ROOT)
            if (parsed.scheme.equals("http", true)) reasons += "Connexion HTTP non chiffrée."
            if (host.startsWith("xn--") || host.contains(".xn--")) reasons += "Nom de domaine internationalisé pouvant masquer des caractères."
            if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) reasons += "Lien utilisant directement une adresse IP."
            if (host.count { it == '.' } >= 4) reasons += "Sous-domaines nombreux."
            if (host.contains("secure-login") || host.contains("verify-account") || host.contains("password-reset")) {
                reasons += "Mots fréquemment utilisés dans des liens d'hameçonnage."
            }
            if (parsed.userInfo != null) reasons += "Le lien contient des informations avant le nom de domaine."
        }
        return LinkRiskReport(value, reasons.isNotEmpty(), reasons)
    }

    /**
     * Manual SAF folder audit. Compares file names, sizes and modification dates
     * with the previous user-authorized scan; it is not a real-time filesystem monitor.
     */
    fun auditFolder(context: Context, treeUri: Uri): FolderRiskReport {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return FolderRiskReport(0, 0, emptyList(), "Dossier inaccessible.")
        val current = JSONObject()
        val suspicious = mutableListOf<String>()
        var inspected = 0
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 8 || inspected >= 1500) return
            dir.listFiles().forEach { file ->
                if (inspected >= 1500) return@forEach
                if (file.isDirectory) walk(file, depth + 1) else if (file.isFile) {
                    inspected++
                    val name = file.name ?: "fichier"
                    val key = file.uri.toString()
                    current.put(key, "${file.length()}:${file.lastModified()}")
                    val lower = name.lowercase(Locale.ROOT)
                    if (lower.endsWith(".locked") || lower.endsWith(".encrypted") ||
                        lower.endsWith(".crypt") || lower.endsWith(".lockbit")) {
                        suspicious += name
                    }
                }
            }
        }
        walk(root, 0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldText = prefs.getString(SNAPSHOT, null)
        var changed = 0
        if (oldText != null) {
            val old = runCatching { JSONObject(oldText) }.getOrNull()
            if (old != null) {
                val keys = old.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!current.has(key) || old.optString(key) != current.optString(key)) changed++
                }
                val newKeys = current.keys()
                while (newKeys.hasNext()) if (!old.has(newKeys.next())) changed++
            }
        }
        prefs.edit().putString(SNAPSHOT, current.toString()).apply()
        val message = when {
            oldText == null -> "Référence créée. Relance une analyse plus tard pour comparer les changements."
            changed >= 20 || suspicious.isNotEmpty() -> "ALERTE : changements importants ou extensions suspectes. Déconnecte le téléphone du réseau et vérifie tes fichiers."
            else -> "Aucun changement massif détecté par cette comparaison."
        }
        return FolderRiskReport(inspected, changed, suspicious.distinct().take(20), message)
    }

    fun setEmergency(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("emergency_mode", enabled).apply()
    }

    fun isEmergency(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("emergency_mode", false)
}
