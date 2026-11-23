package com.ikar.assistant.security

import android.content.Context
import java.io.File

/**
 * Official libyara bridge. This reports unavailable/error states explicitly;
 * it never silently labels the Kotlin subset engine as official YARA.
 */
object NativeYaraEngine {
    data class ScanResult(
        val available: Boolean,
        val matches: List<String> = emptyList(),
        val error: String? = null
    )

    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    @Synchronized
    fun initialize() {
        if (loaded || loadError != null) return
        try {
            System.loadLibrary("ikar_yara")
            loaded = true
        } catch (error: UnsatisfiedLinkError) {
            loadError = error.message ?: "Bibliothèque native indisponible"
        } catch (error: SecurityException) {
            loadError = error.message ?: "Chargement natif refusé"
        }
    }

    private external fun nativeScanFile(filePath: String, rulePath: String): String

    fun scan(context: Context, file: File): ScanResult {
        initialize()
        if (!loaded) return ScanResult(false, error = loadError ?: "Moteur libyara non chargé")
        return try {
            val ruleFile = File(context.filesDir, "ikar-yara/local-rules.yar")
            ruleFile.parentFile?.mkdirs()
            context.assets.open("ikar-yara/local-rules.yar").use { input ->
                ruleFile.outputStream().use { output -> input.copyTo(output) }
            }
            val result = nativeScanFile(file.absolutePath, ruleFile.absolutePath)
            if (result.startsWith("ERROR:")) {
                ScanResult(false, error = result.removePrefix("ERROR:"))
            } else {
                ScanResult(true, matches = result.lineSequence().filter { it.isNotBlank() }.distinct().toList())
            }
        } catch (error: Exception) {
            ScanResult(false, error = error.message ?: error.javaClass.simpleName)
        } catch (error: UnsatisfiedLinkError) {
            ScanResult(false, error = error.message ?: "Erreur JNI")
        }
    }
}
