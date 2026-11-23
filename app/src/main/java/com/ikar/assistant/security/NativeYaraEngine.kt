package com.ikar.assistant.security

import android.content.Context
import java.io.File

/** Official libyara bridge with persistent, app-private YARA Forge rules. */
object NativeYaraEngine {
    data class ScanResult(val available: Boolean, val matches: List<String> = emptyList(), val error: String? = null)
    private const val PREFS = "ikar_yara_forge"
    private const val KEY_GENERATION = "rules_generation"
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

    private external fun nativeScanFile(filePath: String, rulesDirectory: String, generation: Long): String
    private external fun nativeValidateRules(rulesDirectory: String): String

    @Synchronized
    fun rulesDirectory(context: Context): File {
        val root = File(context.filesDir, "ikar-yara")
        val active = File(root, "rules")
        if (!active.exists()) active.mkdirs()
        val hasRules = active.walkTopDown().any {
            it.isFile && (it.extension.equals("yar", true) || it.extension.equals("yara", true))
        }
        if (!hasRules) {
            val seed = File(active, "ikar-starter-rules.yar")
            context.assets.open("ikar-yara/local-rules.yar").use { input ->
                seed.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return active
    }

    @Synchronized
    fun validateRulesDirectory(directory: File): String {
        initialize()
        if (!loaded) return "ERROR:NATIVE_ENGINE_UNAVAILABLE:" + (loadError ?: "unknown")
        return try {
            nativeValidateRules(directory.absolutePath)
        } catch (error: Exception) {
            "ERROR:" + (error.message ?: error.javaClass.simpleName)
        } catch (error: UnsatisfiedLinkError) {
            "ERROR:" + (error.message ?: "JNI validation unavailable")
        }
    }

    @Synchronized
    fun activateRulesDirectory(context: Context, staging: File): Boolean {
        val root = File(context.filesDir, "ikar-yara")
        val active = File(root, "rules")
        val backup = File(root, "rules.previous")
        if (!staging.isDirectory || !staging.exists()) return false
        if (backup.exists()) backup.deleteRecursively()
        val movedOld = active.exists() && active.renameTo(backup)
        if (active.exists() && !movedOld) return false
        if (!staging.renameTo(active)) {
            if (movedOld) backup.renameTo(active)
            return false
        }
        backup.deleteRecursively()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_GENERATION, prefs.getLong(KEY_GENERATION, 0L) + 1L).apply()
        return true
    }

    @Synchronized
    fun scan(context: Context, file: File): ScanResult {
        initialize()
        if (!loaded) return ScanResult(false, error = loadError ?: "Moteur libyara non chargé")
        return try {
            val directory = rulesDirectory(context)
            val generation = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_GENERATION, 0L)
            val result = nativeScanFile(file.absolutePath, directory.absolutePath, generation)
            if (result.startsWith("ERROR:")) ScanResult(false, error = result.removePrefix("ERROR:"))
            else ScanResult(true, matches = result.lineSequence().filter { it.isNotBlank() }.distinct().toList())
        } catch (error: Exception) {
            ScanResult(false, error = error.message ?: error.javaClass.simpleName)
        } catch (error: UnsatisfiedLinkError) {
            ScanResult(false, error = error.message ?: "Erreur JNI")
        }
    }
}
