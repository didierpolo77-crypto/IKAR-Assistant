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
            ?: return Result.failure()
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
                if (status == 401 || status == 403) return Result.failure()
                return Result.retry()
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 8_000_000) return Result.failure()
            val root = JSONObject(body)
            if (root.optString("query_status") != "ok") return Result.retry()
            val items = root.optJSONArray("urls") ?: return Result.retry()
            val active = linkedSetOf<String>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val url = item.optString("url")
                val state = item.optString("url_status")
                if (state == "online" && (url.startsWith("http://") || url.startsWith("https://"))) {
                    active += url
                }
            }
            if (active.isEmpty()) return Result.retry()
            ThreatIntelStore.replaceActiveUrls(applicationContext, active)
            Result.success()
        } catch (_: java.net.SocketTimeoutException) {
            Result.retry()
        } catch (_: java.io.IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        } finally {
            connection?.disconnect()
        }
    }
}
