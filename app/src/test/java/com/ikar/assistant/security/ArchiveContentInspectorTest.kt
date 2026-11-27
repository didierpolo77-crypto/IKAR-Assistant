package com.ikar.assistant.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveContentInspectorTest {
    private fun archive(name: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test
    fun detectsExecutableEntriesWithoutCallingArchiveMalware() {
        val bytes = archive("payload/setup.apk", byteArrayOf(1, 2, 3))
        val result = ArchiveContentInspector.inspect(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertTrue(result.findings.any { it.contains("Entrée exécutable") })
        assertTrue(result.incompleteReason == null)
    }

    @Test
    fun detectsPathTraversalEntries() {
        val bytes = archive("../outside.txt", byteArrayOf(1))
        val result = ArchiveContentInspector.inspect(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertTrue(result.findings.any { it.contains("Chemin d'entrée dangereux") })
    }

    @Test
    fun reportsNestedArchivesAsNotRecursivelyInspected() {
        val bytes = archive("nested.zip", byteArrayOf(1, 2, 3))
        val result = ArchiveContentInspector.inspect(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertTrue(result.findings.any { it.contains("inspection récursive non effectuée") })
    }
}
