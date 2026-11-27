package com.ikar.assistant.security

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

data class DownloadScanSummary(
    val scanned: Int,
    val flagged: List<String>,
    val unscanned: List<String>,
    val limitedEngineFiles: Int
) {
    val hasAlerts: Boolean get() = flagged.isNotEmpty() || unscanned.isNotEmpty()
}

/**
 * Scans files saved by other apps inside a user-authorized shared folder (normally Downloads).
 * It never deletes, moves, opens, or executes user files. New/changed files are scanned locally.
 */
object DownloadedFileScanner {
    private const val PREFS = "ikar_download_scan_state"
    private const val MAX_FILES_PER_RUN = 20
    private const val MAX_DEPTH = 8
    private const val SETTLE_DELAY_MS = 90_000L
    private const val CLEAN_VERDICT = "Aucun indicateur détecté par les règles locales disponibles"

    fun scanTree(context: Context, treeUri: Uri): DownloadScanSummary {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Le dossier sélectionné n'est plus accessible.")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val flagged = mutableListOf<String>()
        val unscanned = mutableListOf<String>()
        var scanned = 0
        var limited = 0
        var deferred = false
        val now = System.currentTimeMillis()

        fun walk(directory: DocumentFile, depth: Int) {
            if (depth > MAX_DEPTH || scanned >= MAX_FILES_PER_RUN) return
            directory.listFiles().forEach { item ->
                if (scanned >= MAX_FILES_PER_RUN) {
                    deferred = true
                    return@forEach
                }
                if (item.isDirectory) {
                    walk(item, depth + 1)
                } else if (item.isFile && item.name?.startsWith(".") != true) {
                    val name = item.name ?: "fichier sans nom"
                    val modified = item.lastModified()
                    val size = item.length()
                    if (modified <= 0L || now - modified < SETTLE_DELAY_MS || size <= 0L) {
                        deferred = true
                        return@forEach
                    }
                    val uriKey = sha256(item.uri.toString())
                    val signature = "$size:$modified"
                    if (prefs.getString(uriKey, null) == signature ||
                        prefs.getString(uriKey, null) == "UNSCANNED:$signature"
                    ) return@forEach

                    try {
                        val report = FileScanner.scan(context, item.uri, enrichOnline = false)
                        scanned++
                        prefs.edit().putString(uriKey, signature).apply()
                        if (report.verdict != CLEAN_VERDICT) {
                            flagged += "$name — ${report.verdict}"
                        }
                        if (report.engines.any { it.contains("Moteur Kotlin YARA-compatible") }) {
                            limited++
                            unscanned += "$name — couverture YARA limitée : libyara natif indisponible ; fichier non certifié sûr."
                        }
                        if (report.engines.any { it.contains("ClamAV natif non embarqué") || it.contains("pont JNI et bases ClamAV à valider") }) {
                            unscanned += "$name — ClamAV n'est pas opérationnel dans cette version ; analyse antivirus multicouche incomplète."
                        }
                    } catch (error: Exception) {
                        scanned++
                        prefs.edit().putString(uriKey, "UNSCANNED:$signature").apply()
                        unscanned += "$name — ${error.localizedMessage ?: error.javaClass.simpleName}"
                    }
                }
            }
        }
        walk(root, 0)
        if (deferred) prefs.edit().putLong("more_files_pending_at", now).apply()
        prefs.edit().putLong("last_scan_at", now).apply()
        return DownloadScanSummary(scanned, flagged.distinct().take(20), unscanned.distinct().take(20), limited)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
