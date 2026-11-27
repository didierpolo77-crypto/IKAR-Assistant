package com.ikar.assistant.security

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Bounded ZIP triage for low-memory devices; not a malware verdict or a substitute for YARA. */
object ArchiveContentInspector {
    private const val MAX_ENTRIES = 100
    private const val MAX_ENTRY_BYTES = 8L * 1024L * 1024L
    private const val MAX_TOTAL_EXPANDED_BYTES = 32L * 1024L * 1024L
    private const val MAX_EXPANSION_RATIO = 100L

    data class Result(val entriesInspected: Int, val findings: List<String>, val incompleteReason: String? = null)

    fun inspect(input: InputStream, compressedSize: Long): Result {
        val findings = linkedSetOf<String>()
        var entries = 0
        var totalExpanded = 0L
        var incomplete: String? = null
        val zip = ZipInputStream(BufferedInputStream(input))
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                entries++
                if (entries > MAX_ENTRIES) { incomplete = "limite de 100 entrées atteinte"; break }

                val normalized = entry.name.orEmpty().replace(92.toChar(), '/')
                if (normalized.startsWith("/") || normalized.split('/').any { it == ".." } ||
                    Regex("^[A-Za-z]:").containsMatchIn(normalized)
                ) findings += "Chemin d'entrée dangereux dans l'archive"

                val leaf = normalized.substringAfterLast('/').lowercase()
                val ext = leaf.substringAfterLast('.', "")
                if (ext in setOf("apk", "exe", "dll", "msi", "bat", "cmd", "ps1", "vbs", "js", "sh", "jar", "dex", "so", "scr")) {
                    findings += "Entrée exécutable dans l'archive : " + leaf
                }
                if (ext in setOf("docm", "xlsm", "pptm", "dotm", "xltm", "potm")) {
                    findings += "Document Office à macros dans l'archive : " + leaf
                }
                if (ext in setOf("zip", "7z", "rar", "gz", "tar")) {
                    findings += "Archive imbriquée détectée : " + leaf + " ; inspection récursive non effectuée"
                }

                val header = ByteArray(4)
                var headerCount = 0
                var entryBytes = 0L
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    if (headerCount < header.size) {
                        val copy = minOf(count, header.size - headerCount)
                        System.arraycopy(buffer, 0, header, headerCount, copy)
                        headerCount += copy
                    }
                    entryBytes += count
                    totalExpanded += count
                    if (entryBytes > MAX_ENTRY_BYTES) { incomplete = "une entrée dépasse 8 Mio"; break }
                    if (totalExpanded > MAX_TOTAL_EXPANDED_BYTES) { incomplete = "décompression limitée à 32 Mio par archive"; break }
                    if (totalExpanded / compressedSize.coerceAtLeast(1L) > MAX_EXPANSION_RATIO) {
                        incomplete = "ratio de décompression supérieur à 100:1"
                        break
                    }
                }
                if (headerCount >= 2 && header[0] == 0x4d.toByte() && header[1] == 0x5a.toByte()) {
                    findings += "Entrée contenant un binaire Windows MZ : " + leaf
                }
                if (headerCount >= 4 && header[0] == 0x7f.toByte() &&
                    header[1] == 0x45.toByte() && header[2] == 0x4c.toByte() && header[3] == 0x46.toByte()
                ) findings += "Entrée contenant un binaire ELF : " + leaf
                if (incomplete != null) break
                zip.closeEntry()
            }
        } catch (failure: Exception) {
            incomplete = "archive illisible ou endommagée (" + failure.javaClass.simpleName + ")"
        } finally {
            runCatching { zip.close() }
        }
        return Result(entries, findings.toList(), incomplete)
    }
}
