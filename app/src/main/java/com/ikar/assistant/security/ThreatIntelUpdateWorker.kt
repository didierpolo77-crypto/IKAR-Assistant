package com.ikar.assistant.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object ThreatIntelScheduler {
    private const val UNIQUE_WORK = "ikar_urlhaus_threat_intel"

    fun enable(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ThreatIntelUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun updateNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = androidx.work.OneTimeWorkRequestBuilder<ThreatIntelUpdateWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}

class ThreatIntelUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private class AuthRejected : Exception()

    override suspend fun doWork(): Result {
        val authKey = ThreatIntelStore.readAuthKey(applicationContext)
            ?: run {
                ThreatIntelStore.setUpdateStatus(applicationContext, "Clé URLhaus absente ou illisible.")
                return Result.failure()
            }
        return try {
            val urlRoot = fetchJson("https://urlhaus-api.abuse.ch/v1/urls/recent/limit/1000/", authKey)
            val payloadRoot = fetchJson("https://urlhaus-api.abuse.ch/v1/payloads/recent/limit/1000/", authKey)

            val urlStatus = urlRoot.optString("query_status")
            if (urlStatus != "ok" && urlStatus != "no_results") {
                ThreatIntelStore.setUpdateStatus(applicationContext, "Réponse URLhaus URLs : $urlStatus.")
                return Result.retry()
            }
            val payloadStatus = payloadRoot.optString("query_status")
            if (payloadStatus != "ok" && payloadStatus != "no_results") {
                ThreatIntelStore.setUpdateStatus(applicationContext, "Réponse URLhaus empreintes : $payloadStatus.")
                return Result.retry()
            }

            val activeUrls = linkedSetOf<String>()
            val urlItems = urlRoot.optJSONArray("urls")
            if (urlItems != null) {
                for (index in 0 until urlItems.length()) {
                    val item = urlItems.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    if (item.optString("url_status") == "online" &&
                        (url.startsWith("http://") || url.startsWith("https://"))
                    ) activeUrls += url
                }
            }

            val hashes = linkedSetOf<String>()
            val payloadItems = payloadRoot.optJSONArray("payloads")
            if (payloadItems != null) {
                for (index in 0 until payloadItems.length()) {
                    val item = payloadItems.optJSONObject(index) ?: continue
                    val hash = item.optString("sha256_hash")
                    if (hash.matches(Regex("[a-fA-F0-9]{64}"))) hashes += hash.lowercase()
                }
            }

            ThreatIntelStore.replaceActiveUrls(applicationContext, activeUrls)
            ThreatIntelStore.mergeKnownSha256(applicationContext, hashes)
            ThreatIntelStore.setUpdateStatus(
                applicationContext,
                "Mise à jour réussie : ${activeUrls.size} URLs actives et ${hashes.size} empreintes SHA-256."
            )
            Result.success()
        } catch (_: AuthRejected) {
            ThreatIntelStore.setUpdateStatus(applicationContext, "Clé URLhaus refusée. Vérifie ton Auth-Key.")
            Result.failure()
        } catch (_: java.net.SocketTimeoutException) {
            ThreatIntelStore.setUpdateStatus(applicationContext, "Délai dépassé pendant la mise à jour URLhaus.")
            Result.retry()
        } catch (_: java.io.IOException) {
            ThreatIntelStore.setUpdateStatus(applicationContext, "Erreur réseau pendant la mise à jour URLhaus.")
            Result.retry()
        } catch (error: Exception) {
            ThreatIntelStore.setUpdateStatus(applicationContext, "Erreur de mise à jour : ${error.javaClass.simpleName}.")
            Result.failure()
        }
    }

    private fun fetchJson(endpoint: String, authKey: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Auth-Key", authKey)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "IKAR-Assistant-Android")
        }
        try {
            val status = connection.responseCode
            if (status == 401 || status == 403) throw AuthRejected()
            if (status !in 200..299) throw java.io.IOException("HTTP $status")
            val body = BoundedResponseReader.readUtf8(connection.inputStream, 8_000_000, "URLhaus")
            if (body.isBlank()) throw java.io.IOException("Réponse URLhaus vide")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
