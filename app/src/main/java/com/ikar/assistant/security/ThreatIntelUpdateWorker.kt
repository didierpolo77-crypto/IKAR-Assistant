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
    override suspend fun doWork(): Result {
        val authKey = ThreatIntelStore.readAuthKey(applicationContext)
            ?: run {
                ThreatIntelStore.setUpdateStatus(applicationContext, "Clé URLhaus absente ou illisible.")
                return Result.failure()
            }
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = "https://urlhaus-api.abuse.ch/v1/urls/recent/limit/1000/"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("Auth-Key", authKey)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "IKAR-Assistant-Android")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                if (status == 401 || status == 403) {
                    ThreatIntelStore.setUpdateStatus(applicationContext, "Clé URLhaus refusée (HTTP $status).")
                    return Result.failure()
                }
                ThreatIntelStore.setUpdateStatus(applicationContext, "Serveur URLhaus temporairement indisponible (HTTP $status).")
                return Result.retry()
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 8_000_000) return Result.failure()
            val root = JSONObject(body)
            if (root.optString("query_status") != "ok") {
                ThreatIntelStore.setUpdateStatus(applicationContext, "URLhaus n'a pas confirmé la requête : ${root.optString("query_status")}.")
                return Result.retry()
            }
            val items = root.optJSONArray("urls") ?: run {
                ThreatIntelStore.setUpdateStatus(applicationContext, "Réponse URLhaus invalide : liste absente.")
                return Result.retry()
            }
            val active = linkedSetOf<String>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val url = item.optString("url")
                val state = item.optString("url_status")
                if (state == "online" && (url.startsWith("http://") || url.startsWith("https://"))) {
                    active += url
                }
            }
            if (active.isEmpty()) {
                ThreatIntelStore.setUpdateStatus(applicationContext, "Aucun indicateur actif reçu ; ancienne base conservée.")
                return Result.retry()
            }
            ThreatIntelStore.replaceActiveUrls(applicationContext, active)
            ThreatIntelStore.setUpdateStatus(applicationContext, "Mise à jour réussie : ${active.size} URLs actives reçues.")
            Result.success()
        } catch (_: java.net.SocketTimeoutException) {
            ThreatIntelStore.setUpdateStatus(applicationContext, "Délai dépassé pendant la mise à jour URLhaus.")
            Result.retry()
        } catch (_: java.io.IOException) {
            ThreatIntelStore.setUpdateStatus(applicationContext, "Erreur réseau pendant la mise à jour URLhaus.")
            Result.retry()
        } catch (error: Exception) {
            ThreatIntelStore.setUpdateStatus(applicationContext, "Erreur de mise à jour : ${error.javaClass.simpleName}.")
            Result.failure()
        } finally {
            connection?.disconnect()
        }
    }
}
