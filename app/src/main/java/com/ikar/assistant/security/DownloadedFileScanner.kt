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
 * This is post-download detection: Android does not let an ordinary app intercept and veto
 * every other app's HTTPS download before it is written. No user file is deleted or moved.
 */
object DownloadedFileScanner {
    private const val PREFS = "ikar_download_scan_state"
    private const val MAX_FILES_PER_RUN = 20
    private const val MAX_DEPTH = 8
    private const val SETTLE_DELAY_MS = 90_000L
    private const val SCANNER_REVISION = "v2"
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
            if (depth > MAX_DEPTH) {
                deferred = true
                return
            }
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
                    if (modified <= 0L || now - modified < SETTLE_DELAY_MS || size < 0L) {
                        deferred = true
                        return@forEach
                    }
                    val uriKey = sha256(item.uri.toString())
                    val signature = "$SCANNER_REVISION:$size:$modified"
                    val previous = prefs.getString(uriKey, null)
                    when {
                        previous == "CLEAN:$signature" -> return@forEach
                        previous == "FLAGGED:$signature" -> {
                            flagged += "$name — indicateur déjà détecté lors d'une analyse précédente ; fichier à examiner/isoler."
                            return@forEach
                        }
                        previous == "UNSCANNED:$signature" -> {
                            unscanned += "$name — analyse précédente incomplète ; ne pas considérer ce fichier comme sûr."
                            return@forEach
                        }
                    }

                    try {
                        val header = ByteArray(16)
                        context.contentResolver.openInputStream(item.uri)?.use { input ->
                            var offset = 0
                            while (offset < header.size) {
                                val count = input.read(header, offset, header.size - offset)
                                if (count <= 0) break
                                offset += count
                            }
                        } ?: throw IllegalStateException("Impossible de lire l'en-tête du fichier.")
                        val risk = DownloadRiskInspector.inspect(
                            name,
                            context.contentResolver.getType(item.uri),
                            header
                        )
                        val report = FileScanner.scan(context, item.uri, enrichOnline = false)
                        scanned++

                        var archiveFindings = emptyList<String>()
                        var archiveIncomplete: String? = null
                        val isZip = header.size >= 4 && header[0] == 0x50.toByte() &&
                            header[1] == 0x4b.toByte() && header[2] == 0x03.toByte() &&
                            header[3] == 0x04.toByte()
                        if (isZip) {
                            val archiveResult = context.contentResolver.openInputStream(item.uri)?.use { input ->
                                ArchiveContentInspector.inspect(input, size)
                            } ?: throw IllegalStateException("Impossible de relire l'archive.")
                            archiveFindings = archiveResult.findings
                            archiveIncomplete = archiveResult.incompleteReason
                        }

                        val highRisk = risk.highRisk
                        val caution = risk.caution
                        val reportFlagged = report.verdict != CLEAN_VERDICT
                        if (reportFlagged) flagged += "$name — ${report.verdict}"
                        if (highRisk.isNotEmpty()) {
                            flagged += "$name — risque élevé : ${highRisk.joinToString("; ")}"
                        }
                        if (caution.isNotEmpty()) {
                            flagged += "$name — prudence : ${caution.joinToString("; ")}"
                        }
                        if (archiveFindings.isNotEmpty()) {
                            flagged += "$name — contenu d'archive à examiner : ${archiveFindings.joinToString("; ")}"
                        }

                        val partialReasons = mutableListOf<String>()
                        if (archiveIncomplete != null) {
                            partialReasons += "inspection d'archive incomplète : $archiveIncomplete"
                        }
                        if (report.engines.any { it.contains("Moteur Kotlin YARA-compatible") }) {
                            limited++
                            partialReasons += "libyara natif indisponible ; sous-ensemble YARA seulement"
                        }
                        if (report.engines.any {
                                it.contains("ClamAV natif non embarqué") ||
                                    it.contains("pont JNI et bases ClamAV à valider")
                            }
                        ) {
                            partialReasons += "ClamAV non opérationnel ; analyse multicouche incomplète"
                        }
                        if (partialReasons.isNotEmpty()) {
                            unscanned += "$name — ${partialReasons.joinToString("; ")}. Fichier non certifié sûr."
                            prefs.edit().putString(uriKey, "UNSCANNED:$signature").apply()
                        } else if (reportFlagged || highRisk.isNotEmpty() || caution.isNotEmpty()) {
                            prefs.edit().putString(uriKey, "FLAGGED:$signature").apply()
                        } else {
                            prefs.edit().putString(uriKey, "CLEAN:$signature").apply()
                        }
                    } catch (error: Exception) {
                        scanned++
                        prefs.edit().putString(uriKey, "UNSCANNED:$signature").apply()
                        unscanned += "$name — ${error.localizedMessage ?: error.javaClass.simpleName}. Fichier non vérifié."
                    }
                }
            }
        }

        walk(root, 0)
        if (deferred) prefs.edit().putLong("more_files_pending_at", now).apply()
        prefs.edit().putLong("last_scan_at", now).apply()
        return DownloadScanSummary(
            scanned,
            flagged.distinct().take(20),
            unscanned.distinct().take(20),
            limited
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
