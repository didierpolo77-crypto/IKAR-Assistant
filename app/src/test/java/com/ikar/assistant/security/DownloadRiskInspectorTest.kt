package com.ikar.assistant.security

import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRiskInspectorTest {
    @Test
    fun flagsAndroidPackagesAndExecutableExtensions() {
        val result = DownloadRiskInspector.inspect("update.apk", "application/vnd.android.package-archive", byteArrayOf())
        assertTrue(result.highRisk.isNotEmpty())
    }

    @Test
    fun detectsExecutableContentHiddenBehindAnImageExtension() {
        val result = DownloadRiskInspector.inspect(
            "photo.jpg",
            "image/jpeg",
            byteArrayOf(0x4d, 0x5a, 0x90.toByte(), 0x00)
        )
        assertTrue(result.highRisk.any { it.contains("Windows PE") || it.contains("MZ") })
        assertTrue(result.highRisk.any { it.contains("ne correspond pas") })
    }

    @Test
    fun detectsDoubleExtensionExecutableNames() {
        val result = DownloadRiskInspector.inspect("facture.pdf.exe", "application/octet-stream", byteArrayOf())
        assertTrue(result.highRisk.any { it.contains("Double extension") })
    }

    @Test
    fun flagsArchivesForAdditionalInspectionWithoutCallingThemMalware() {
        val result = DownloadRiskInspector.inspect("documents.zip", "application/zip", byteArrayOf())
        assertTrue(result.caution.any { it.contains("Archive") })
        assertTrue(result.highRisk.isEmpty())
    }

    @Test
    fun detectsUnixExecutableSignatureEvenWithoutExecutableExtension() {
        val result = DownloadRiskInspector.inspect(
            "notes.txt",
            "text/plain",
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
        )
        assertTrue(result.highRisk.any { it.contains("ELF") })
    }
}
