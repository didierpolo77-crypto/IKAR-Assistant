package com.ikar.assistant.security

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager

/** Per-UID byte totals reported by Android; this is usage accounting, not packet inspection. */
data class AppNetworkUsage(
    val uid: Int,
    val appName: String,
    val packages: List<String>,
    val receivedBytes: Long,
    val sentBytes: Long
) {
    val totalBytes: Long get() = receivedBytes + sentBytes
}

object AppNetworkUsageMonitor {
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * Reads the last [days] of Wi-Fi and mobile byte counters, grouped by Android UID.
     * Android may aggregate shared UIDs and can delay updates; no remote hosts or URLs
     * are exposed by NetworkStatsManager.
     */
    fun queryRecentUsage(context: Context, days: Int = 1): List<AppNetworkUsage> {
        require(days in 1..30) { "La période doit être comprise entre 1 et 30 jours." }
        check(hasUsageAccess(context)) { "L'accès aux statistiques d'utilisation n'est pas autorisé." }

        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = end - days * 24L * 60L * 60L * 1000L
        val totals = linkedMapOf<Int, LongArray>()

        fun collect(networkType: Int) {
            val stats = manager.querySummary(networkType, null, start, end)
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    if (!stats.getNextBucket(bucket)) break
                    val uid = bucket.uid
                    if (uid < 10_000) continue
                    val bytes = totals.getOrPut(uid) { longArrayOf(0L, 0L) }
                    bytes[0] += bucket.rxBytes.coerceAtLeast(0L)
                    bytes[1] += bucket.txBytes.coerceAtLeast(0L)
                }
            } finally {
                stats.close()
            }
        }

        // A transport may be unavailable on a device; keep results from the other one.
        runCatching { collect(ConnectivityManager.TYPE_WIFI) }
        runCatching { collect(ConnectivityManager.TYPE_MOBILE) }

        val pm = context.packageManager
        return totals.map { (uid, bytes) ->
            val packageNames = pm.getPackagesForUid(uid)?.toList().orEmpty()
            val labels = packageNames.mapNotNull { packageName ->
                runCatching {
                    val info = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(info).toString()
                }.getOrNull()
            }.distinct()
            AppNetworkUsage(
                uid = uid,
                appName = labels.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: "Application système (UID $uid)",
                packages = packageNames,
                receivedBytes = bytes[0],
                sentBytes = bytes[1]
            )
        }.filter { it.totalBytes > 0L }
            .sortedByDescending { it.totalBytes }
    }
}
