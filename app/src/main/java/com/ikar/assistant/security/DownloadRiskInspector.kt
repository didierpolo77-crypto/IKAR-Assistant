package com.ikar.assistant.security

/**
 * Conservative file-type triage for shared downloads.
 *
 * This class identifies executable formats and risky containers from both names and
 * file signatures. It does not claim that a file is malware, and it does not delete
 * or block another application's file after Android has saved it.
 */
object DownloadRiskInspector {
    private val executableExtensions = setOf(
        "apk", "xapk", "apks", "aab", "exe", "dll", "com", "scr", "pif",
        "msi", "msp", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse",
        "hta", "wsf", "wsh", "sh", "bash", "zsh", "jar", "class", "dex",
        "so", "dylib", "dmg", "pkg", "deb", "rpm", "lnk", "reg"
    )
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "img")
    private val macroExtensions = setOf("docm", "xlsm", "pptm", "dotm", "xltm", "potm")
    private val executableMimeTypes = setOf(
        "application/vnd.android.package-archive", "application/x-msdownload",
        "application/x-dosexec", "application/x-executable", "application/x-sh",
        "application/x-shellscript", "application/java-archive", "application/x-java-archive",
        "application/x-sharedlib", "application/x-msdos-program", "application/javascript",
        "application/ecmascript", "text/javascript", "text/vbscript"
    )

    data class Result(
        val highRisk: List<String>,
        val caution: List<String>
    ) {
        val requiresAttention: Boolean get() = highRisk.isNotEmpty() || caution.isNotEmpty()
    }

    fun inspect(fileName: String, mimeType: String?, header: ByteArray): Result {
        val name = fileName.lowercase().substringAfterLast('/')
        val suffixes = name.split('.').drop(1)
        val extension = suffixes.lastOrNull().orEmpty()
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
        val high = linkedSetOf<String>()
        val caution = linkedSetOf<String>()

        if (extension in executableExtensions) {
            high += "Extension exécutable ou installable : .$extension"
        }
        if (extension in executableExtensions && suffixes.size > 1) {
            high += "Double extension avec suffixe exécutable final (ex. document.pdf.exe)"
        }
        if (mime in executableMimeTypes || mime.startsWith("application/x-executable")) {
            high += "Type MIME associé à du code exécutable : $mime"
        }

        val magic = when {
            header.size >= 4 && header[0] == 0x7f.toByte() &&
                header[1] == 0x45.toByte() && header[2] == 0x4c.toByte() && header[3] == 0x46.toByte() ->
                "Binaire ELF (exécutable ou bibliothèque)"
            header.size >= 2 && header[0] == 0x4d.toByte() && header[1] == 0x5a.toByte() ->
                "Binaire Windows PE/DOS (signature MZ)"
            header.size >= 4 && header[0] == 0xca.toByte() && header[1] == 0xfe.toByte() &&
                header[2] == 0xba.toByte() && header[3] == 0xbe.toByte() ->
                "Binaire Java class"
            header.size >= 4 && header[0] == 0x64.toByte() && header[1] == 0x65.toByte() &&
                header[2] == 0x78.toByte() && header[3] == 0x0a.toByte() ->
                "Binaire Android DEX"
            header.size >= 2 && header[0] == 0x23.toByte() && header[1] == 0x21.toByte() ->
                "Script avec ligne shebang"
            header.size >= 4 && (
                header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xfe.toByte(), 0xed.toByte(), 0xfa.toByte(), 0xce.toByte())) ||
                header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xce.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte())) ||
                header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xfe.toByte(), 0xed.toByte(), 0xfa.toByte(), 0xcf.toByte())) ||
                header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte()))
            ) -> "Binaire Mach-O"
            else -> null
        }
        if (magic != null) {
            high += "Signature de contenu détectée : $magic"
            if (extension !in executableExtensions) {
                high += "Le contenu exécutable ne correspond pas à l'extension .$extension"
            }
        }

        if (extension in archiveExtensions || mime in setOf(
                "application/zip", "application/x-7z-compressed", "application/vnd.rar",
                "application/x-rar-compressed"
            )
        ) {
            caution += "Archive : son contenu interne doit être inspecté avant ouverture"
        }
        if (extension in macroExtensions || mime.contains("macroenabled")) {
            caution += "Document Office pouvant contenir des macros"
        }
        if (mime in setOf("application/octet-stream", "binary/octet-stream", "application/binary")) {
            caution += "Type MIME générique : type réel du fichier non confirmé"
        }
        return Result(high.toList(), caution.toList())
    }
}
