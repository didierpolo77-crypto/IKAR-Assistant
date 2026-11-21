package com.ikar.assistant.security

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ikar.assistant.data.MemoryDatabase
import com.ikar.assistant.data.MemoryEntry
import java.util.concurrent.TimeUnit

object FolderAuditScheduler {
    private const val UNIQUE_WORK = "ikar_authorized_folder_audit"

    fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<FolderAuditWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<FolderAuditWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ikar_authorized_folder_audit_event",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}

class FolderAuditWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("ikar_security_center", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("folder_monitoring_enabled", false)) return Result.success()
        val rawUri = prefs.getString("monitored_folder_uri", null) ?: return Result.success()
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return Result.failure()

        return try {
            val report = SecurityCenter.auditFolder(applicationContext, uri)
            val alert = report.alert
            if (alert) {
                val message = "Ajouts : ${report.added}, modifications : ${report.modified}, suppressions : ${report.removed}. Fichiers à extension suspecte : ${report.suspiciousNames.size}. Vérifie le dossier et tes sauvegardes."
                MemoryDatabase.getInstance(applicationContext).memoryDao().insert(
                    MemoryEntry(type = "SECURITY_EVENT", content = "Alerte anti-ransomware en arrière-plan — $message")
                )
                showAlert(message)
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showAlert(message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("ikar_security_alerts", "Alertes de sécurité IKAR", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(applicationContext, "ikar_security_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("IKAR — alerte de sécurité")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(3501, notification)
    }
}
