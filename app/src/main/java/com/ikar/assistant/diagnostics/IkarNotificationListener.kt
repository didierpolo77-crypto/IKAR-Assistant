package com.ikar.assistant.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * Observes notifications from every other installed app after the user grants
 * Android's notification-listener access. Notification text is inspected only
 * in memory and is never persisted or sent to a backend.
 */
class IkarNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = buildString {
            append(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let {
                if (it.isNotBlank()) append(" ").append(it)
            }
        }
        val classification = classifyNotification(title, body)
        val appLabel = applicationLabel(sbn.packageName)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val count = prefs.getInt("notifications_seen", 0) + 1
        prefs.edit()
            .putLong("last_seen_at", System.currentTimeMillis())
            .putString("last_source_package", sbn.packageName)
            .putString("last_category", classification)
            .putInt("notifications_seen", count)
            .putInt(
                "sensitive_count",
                prefs.getInt("sensitive_count", 0) +
                    if (classification != "Notification ordinaire — contenu non conservé") 1 else 0
            )
            .apply()

        // Every posted notification/update gets its own IKAR alert. The original
        // title and body are deliberately not copied into IKAR's alert.
        postIkarAlert(
            "Notification reçue · $appLabel",
            "Une notification de cette application vient d'arriver ou d'être actualisée."
        )
    }

    @Suppress("DEPRECATION")
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val appLabel = applicationLabel(sbn.packageName)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit()
            .putLong("last_removed_at", System.currentTimeMillis())
            .putString("last_removed_package", sbn.packageName)
            .putInt("notifications_removed", prefs.getInt("notifications_removed", 0) + 1)
            .apply()

        postIkarAlert(
            "Notification retirée · $appLabel",
            "Une notification de cette application a été supprimée ou effacée."
        )
    }

    private fun applicationLabel(packageName: String): String =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

    private fun postIkarAlert(title: String, message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL,
                    "Alertes des notifications surveillées",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Signale les notifications reçues et retirées par les applications."
                }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ALERT_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .build()
        try {
            manager.notify((System.nanoTime() and 0x7fffffff).toInt(), notification)
        } catch (_: SecurityException) {
            // Android 13+ may deny IKAR's own notification permission.
        }
    }

    companion object {
        private const val PREFS = "ikar_notification_privacy"
        private const val ALERT_CHANNEL = "ikar_all_app_notifications"

        internal fun classifyNotification(title: String, body: String): String {
            val text = (title + " " + body).lowercase(Locale.ROOT)
            val otpPattern = Regex("""\b\d{4,8}\b""")
            val hasOtpContext = listOf(
                "code", "verification", "vérification", "otp", "one-time",
                "mot de passe", "password", "authentification", "security code"
            ).any { text.contains(it) }
            if (hasOtpContext && otpPattern.containsMatchIn(text)) {
                return "Sensible — contenu non conservé"
            }
            if (listOf("banque", "bank", "paiement", "payment", "solde", "transaction")
                    .any { text.contains(it) }) {
                return "Finances — contenu non conservé"
            }
            return "Notification ordinaire — contenu non conservé"
        }
    }
}
