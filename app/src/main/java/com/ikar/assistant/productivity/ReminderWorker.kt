package com.ikar.assistant.productivity

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ikar.assistant.data.MemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("reminder_title")?.take(180) ?: return Result.failure()
        val reminderId = inputData.getLong("reminder_id", -1L)
        if (reminderId <= 0L) return Result.failure()
        val stillOpen = withContext(Dispatchers.IO) {
            MemoryDatabase.getInstance(applicationContext).memoryDao().getAll()
                .any { it.id == reminderId && it.type == "TASK" && it.content.startsWith("OPEN|") }
        }
        if (!stillOpen) return Result.success()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rappels IKAR", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Rappel IKAR")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(
            (inputData.getLong("reminder_id", System.currentTimeMillis()) % Int.MAX_VALUE).toInt(),
            notification
        )
        return Result.success()
    }
    companion object { private const val CHANNEL_ID = "ikar_productivity_reminders" }
}
