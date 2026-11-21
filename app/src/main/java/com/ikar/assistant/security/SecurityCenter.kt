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
        val reasons = linkedSetOf<String>()
        val parsed = runCatching { URI(value) }.getOrNull()
        if (value.length > 2048) reasons += "Adresse anormalement longue."
        if (parsed == null || parsed.scheme?.lowercase(Locale.ROOT) !in listOf("http", "https") ||
            parsed.host.isNullOrBlank()
        ) {
            reasons += "Adresse web invalide ou protocole non pris en charge."
            return LinkRiskReport(value, true, reasons.toList())
        }

        val host = parsed.host.lowercase(Locale.ROOT).trimEnd('.')
        val asciiHost = runCatching {
            java.net.IDN.toASCII(host, java.net.IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }.getOrDefault(host)
        val unicodeHost = runCatching { java.net.IDN.toUnicode(asciiHost) }.getOrDefault(host)
        if (!parsed.scheme.equals("https", true)) reasons += "Connexion HTTP non chiffrée."
        if (unicodeHost != asciiHost) reasons += "Nom de domaine internationalisé : vérifie attentivement les caractères."
        if (asciiHost.startsWith("xn--") || asciiHost.contains(".xn--")) reasons += "Nom de domaine punycode pouvant masquer des caractères ressemblants."
        val ipv4 = asciiHost.matches(Regex("""^\\d{1,3}(\\.\\d{1,3}){3}$"""))
        if (ipv4) {
            reasons += "Lien utilisant directement une adresse IP plutôt qu'un nom de domaine."
            val octets = asciiHost.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it in 0..255 }) {
                val first = octets[0]
                val second = octets[1]
                if (first == 10 || first == 127 || first == 0 ||
                    (first == 192 && second == 168) ||
                    (first == 172 && second in 16..31) ||
                    (first == 169 && second == 254) ||
                    (first == 100 && second in 64..127)) {
                    reasons += "Adresse IP privée, locale ou réservée : vérifie que tu connais le réseau ciblé."
                }
            } else {
                reasons += "Adresse IPv4 mal formée."
            }
        }
        if (asciiHost.count { it == '.' } >= 4) reasons += "Sous-domaines particulièrement nombreux."
        if (asciiHost.split('.').any { it.isBlank() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) {
            reasons += "Structure du nom de domaine inhabituelle."
        }
        if (parsed.userInfo != null) reasons += "Le lien contient des informations avant le nom de domaine : technique parfois utilisée pour tromper."
        if (parsed.port != -1 && parsed.port !in setOf(80, 443)) reasons += "Port réseau non standard : vérifie la destination avant de continuer."
        if (asciiHost in setOf("bit.ly", "tinyurl.com", "t.co", "is.gd", "cutt.ly", "rb.gy", "tiny.cc", "shorturl.at", "ow.ly")) {
            reasons += "Lien raccourci : la destination réelle est masquée."
        }
        val pathAndQuery = (parsed.rawPath.orEmpty() + "?" + parsed.rawQuery.orEmpty()).lowercase(Locale.ROOT)
        if (listOf("login", "verify", "password", "wallet", "bank", "secure", "account").any { it in pathAndQuery }) {
            reasons += "Le chemin contient des termes souvent utilisés dans des pages de connexion ou de vérification."
        }
        if (listOf("redirect=", "return=", "continue=", "next=", "url=").any { it in pathAndQuery }) {
            reasons += "Paramètre de redirection détecté : vérifie la destination finale."
        }
        val brandDomains = mapOf(
            "paypal" to "paypal.com",
            "google" to "google.com",
            "facebook" to "facebook.com",
            "microsoft" to "microsoft.com",
            "apple" to "apple.com",
            "amazon" to "amazon.com"
        )
        brandDomains.forEach { (brand, officialDomain) ->
            if (asciiHost.contains(brand) && asciiHost != officialDomain && !asciiHost.endsWith(".$officialDomain")) {
                reasons += "Le nom de domaine mentionne $brand sans correspondre à son domaine officiel habituel."
            }
        }
        val suspiciousTlds = setOf(".zip", ".mov", ".click", ".work", ".support", ".country", ".kim", ".top", ".xyz", ".gq", ".tk", ".ml", ".cf", ".ga")
        if (suspiciousTlds.any { asciiHost.endsWith(it) }) reasons += "Extension de domaine parfois utilisée dans des campagnes abusives ; vérification supplémentaire conseillée."
        val riskyDownload = listOf(".apk", ".exe", ".msi", ".scr", ".bat", ".cmd", ".jar", ".js", ".zip")
            .any { pathAndQuery.substringBefore('?').endsWith(it) }
        if (riskyDownload) reasons += "Le lien semble pointer vers un fichier téléchargeable potentiellement exécutable ou compressé."

        return LinkRiskReport(value, reasons.isNotEmpty(), reasons.toList())
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
