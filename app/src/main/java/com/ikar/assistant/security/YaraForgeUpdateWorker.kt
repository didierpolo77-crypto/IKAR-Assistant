package com.ikar.assistant.security

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object YaraForgeRulesStatus {
    private const val PREFS = "ikar_yara_forge"
    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("status", "Base YARA Forge non encore téléchargée.") ?: "Base YARA Forge non encore téléchargée."
    fun version(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("version", "") ?: ""
    fun ruleFileCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("rule_files", 0)
}

object YaraForgeRulesScheduler {
    private const val PERIODIC_WORK = "ikar_yara_forge_daily_update"
    private const val IMMEDIATE_WORK = "ikar_yara_forge_initial_update"

    fun enable(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<YaraForgeUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun updateNow(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<YaraForgeUpdateWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, request)
    }
}

class YaraForgeUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    companion object {
        private const val PREFS = "ikar_yara_forge"
        private const val RELEASE_API = "https://api.github.com/repos/YARAHQ/yara-forge/releases/latest"
        private const val ASSET_NAME = "yara-forge-rules-full.zip"
        private const val MAX_ARCHIVE_BYTES = 10 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 128L * 1024L * 1024L
        private const val MAX_ENTRIES = 15000
    }

    override suspend fun doWork(): Result {
        return try {
            NativeYaraEngine.initialize()
            val release = fetchJson(RELEASE_API)
            val tag = release.optString("tag_name")
            if (tag.isBlank() || !tag.matches(Regex("[0-9]{8}"))) {
                return fail("Réponse YARA Forge invalide : version absente.")
            }

            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val activeRules = NativeYaraEngine.rulesDirectory(applicationContext)
            if (prefs.getString("version", "") == tag &&
                activeRules.walkTopDown().any { it.isFile && (it.extension.equals("yar", true) || it.extension.equals("yara", true)) }
            ) {
                prefs.edit().putString("status", "Base YARA Forge déjà à jour ($tag).").apply()
                return Result.success()
            }

            val assets = release.optJSONArray("assets") ?: return fail("Aucun paquet dans la publication YARA Forge.")
            var asset: JSONObject? = null
            for (index in 0 until assets.length()) {
                val candidate = assets.optJSONObject(index) ?: continue
                if (candidate.optString("name") == ASSET_NAME) { asset = candidate; break }
            }
            val selected = asset ?: return fail("Paquet YARA Forge core introuvable.")
            val downloadUrl = selected.optString("browser_download_url")
            val expectedDigest = selected.optString("digest").removePrefix("sha256:").lowercase(Locale.ROOT)
            val expectedSize = selected.optLong("size", -1L)
            if (!downloadUrl.startsWith("https://github.com/YARAHQ/yara-forge/releases/download/") ||
                !expectedDigest.matches(Regex("[a-f0-9]{64}")) ||
                expectedSize <= 0 || expectedSize > MAX_ARCHIVE_BYTES
            ) return fail("Métadonnées du paquet YARA Forge refusées.")

            val archive = downloadBytes(downloadUrl, MAX_ARCHIVE_BYTES)
            if (expectedSize != archive.size.toLong()) return fail("Taille du paquet YARA Forge incorrecte.")
            val actualDigest = sha256(archive)
            if (actualDigest != expectedDigest) return fail("Empreinte SHA-256 du paquet YARA Forge incorrecte.")

            val root = File(applicationContext.filesDir, "ikar-yara").apply { mkdirs() }
            val staging = File(root, "rules.staging")
            if (staging.exists()) staging.deleteRecursively()
            if (!staging.mkdirs()) return fail("Impossible de créer le dossier temporaire des règles.")

            val extracted = extractRules(archive, staging)
            if (extracted <= 0) {
                staging.deleteRecursively()
                return fail("Le paquet YARA Forge ne contient aucune règle exploitable.")
            }

            // Keep IKAR's own starter detections alongside the downloaded community rules.
            var localRules = File(staging, "ikar-local-rules.yar")
            if (localRules.exists()) localRules = File(staging, "ikar-local-rules-internal.yar")
            applicationContext.assets.open("ikar-yara/local-rules.yar").use { input ->
                FileOutputStream(localRules).use { output -> input.copyTo(output) }
            }

            val validation = NativeYaraEngine.validateRulesDirectory(staging)
            if (!validation.startsWith("OK:")) {
                staging.deleteRecursively()
                return fail("Règles YARA Forge rejetées par libyara : " + validation.removePrefix("ERROR:"))
            }
            if (!NativeYaraEngine.activateRulesDirectory(applicationContext, staging)) {
                staging.deleteRecursively()
                return fail("Impossible d'activer la nouvelle base ; l'ancienne base est conservée.")
            }

            val validatedFiles = validation.removePrefix("OK:").toIntOrNull() ?: extracted
            prefs.edit()
                .putString("version", tag)
                .putInt("rule_files", validatedFiles)
                .putLong("updated_at", System.currentTimeMillis())
                .putString("sha256", actualDigest)
                .putString("status", "Base YARA Forge $tag installée et validée par libyara : $validatedFiles fichiers de règles.")
                .apply()
            Result.success()
        } catch (error: java.io.IOException) {
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("status", "Mise à jour YARA Forge interrompue : réseau ou téléchargement indisponible.").apply()
            Result.retry()
        } catch (error: Exception) {
            fail("Mise à jour YARA Forge non appliquée : " + (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun fetchJson(endpoint: String): JSONObject {
        val bytes = requestBytes(endpoint, 1_500_000)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun downloadBytes(endpoint: String, limit: Int): ByteArray = requestBytes(endpoint, limit)

    private fun requestBytes(endpoint: String, limit: Int): ByteArray {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            setRequestProperty("User-Agent", "IKAR-Assistant-Android-YaraUpdater")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("HTTP $status")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw java.io.IOException("Taille maximale dépassée")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRules(archive: ByteArray, destination: File): Int {
        val rootPath = destination.canonicalPath + File.separator
        var ruleFiles = 0
        var entryCount = 0
        var expandedBytes = 0L
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) throw java.io.IOException("Trop de fichiers dans l'archive")
                val rawName = entry.name.replace('\\', '/')
                if (entry.isDirectory) {
                    val directoryName = rawName.trimEnd('/')
                    if (directoryName.startsWith("/") || directoryName.split('/').any { it == ".." || it.isBlank() }) {
                        throw java.io.IOException("Chemin dangereux dans l'archive")
                    }
                    zip.closeEntry()
                    continue
                }
                if (rawName.startsWith("/") || rawName.split('/').any { it == ".." || it.isBlank() }) {
                    throw java.io.IOException("Chemin dangereux dans l'archive")
                }
                val extension = rawName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val baseName = rawName.substringAfterLast('/').uppercase(Locale.ROOT)
                val accepted = extension in setOf("yar", "yara", "inc", "txt", "md", "json", "yml", "yaml", "license", "licence", "toml") ||
                    baseName in setOf("LICENSE", "COPYING", "NOTICE", "AUTHORS")
                if (!accepted) { zip.closeEntry(); continue }
                val target = File(destination, rawName)
                target.parentFile?.mkdirs()
                if (!target.canonicalPath.startsWith(rootPath)) throw java.io.IOException("Sortie d'archive hors dossier")
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var fileBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        fileBytes += count
                        expandedBytes += count
                        if (expandedBytes > MAX_EXPANDED_BYTES || fileBytes > 32L * 1024L * 1024L) {
                            throw java.io.IOException("Archive YARA Forge décompressée trop volumineuse")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                if (extension == "yar" || extension == "yara") ruleFiles++
                zip.closeEntry()
            }
        }
        return ruleFiles
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fail(message: String): Result {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("status", message.take(300)).apply()
        return Result.failure()
    }
}
