package com.ikar.assistant.diagnostics

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * User-enabled notification listener. Notification text is inspected in memory only.
 * Raw titles and message bodies are never persisted or sent to a backend.
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
        val prefs = getSharedPreferences("ikar_notification_privacy", MODE_PRIVATE)
        prefs.edit()
            .putLong("last_seen_at", System.currentTimeMillis())
            .putString("last_source_package", sbn.packageName)
            .putString("last_category", classification)
            .putInt("notifications_seen", prefs.getInt("notifications_seen", 0) + 1)
            .putInt(
                "sensitive_count",
                prefs.getInt("sensitive_count", 0) + if (classification != "Notification ordinaire — contenu non conservé") 1 else 0
            )
            .apply()
    }

    companion object {
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
