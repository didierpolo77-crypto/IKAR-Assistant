package com.ikar.assistant.security

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.Context

/**
 * Observes only shared MediaStore change notifications while IKAR is in foreground.
 * It does not inspect file contents, other apps' private storage, or process memory.
 */
object SharedStorageBehaviorMonitor {
    private var observer: ContentObserver? = null
    private var resolver: ContentResolver? = null
    private const val PREFS = "ikar_behavior_monitor"

    @Synchronized
    fun start(context: Context) {
        if (observer != null) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val now = System.currentTimeMillis()
                val windowStart = prefs.getLong("window_start", 0L)
                val count = if (now - windowStart > 60_000L) 1 else prefs.getInt("change_count", 0) + 1
                prefs.edit()
                    .putLong("window_start", if (now - windowStart > 60_000L) now else windowStart)
                    .putInt("change_count", count)
                    .putLong("last_event_at", now)
                    .apply()
                if (count >= 25) {
                    prefs.edit()
                        .putLong("burst_detected_at", now)
                        .putInt("burst_count", count)
                        .apply()
                }
            }
        }
        try {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                contentObserver
            )
            resolver = appContext.contentResolver
            observer = contentObserver
        } catch (_: Exception) {
            observer = null
            resolver = null
        }
    }

    @Synchronized
    fun stop() {
        val current = observer ?: return
        runCatching { resolver?.unregisterContentObserver(current) }
        observer = null
        resolver = null
    }

    fun recentChangeCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val start = prefs.getLong("window_start", 0L)
        return if (System.currentTimeMillis() - start <= 60_000L) prefs.getInt("change_count", 0) else 0
    }

    fun recordPackageInstall(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val start = prefs.getLong("install_window_start", 0L)
        val count = if (now - start > 10 * 60_000L) 1 else prefs.getInt("install_count", 0) + 1
        prefs.edit()
            .putLong("install_window_start", if (now - start > 10 * 60_000L) now else start)
            .putInt("install_count", count)
            .putLong("last_install_at", now)
            .apply()
    }

    fun recentInstallCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val start = prefs.getLong("install_window_start", 0L)
        return if (System.currentTimeMillis() - start <= 10 * 60_000L) prefs.getInt("install_count", 0) else 0
    }

    fun recentBurstDetected(context: Context): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("burst_detected_at", 0L)
        return at > 0L && System.currentTimeMillis() - at <= 10 * 60_000L
    }
}
