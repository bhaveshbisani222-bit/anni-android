package com.jarvis.assistant.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

data class CapturedMessage(
    val appName: String,
    val sender: String,
    val text: String,
    val timestamp: Long
)

/**
 * Listens to incoming notifications (WhatsApp, SMS, Gmail, Missed Calls) so JARVIS can read them aloud.
 */
class JarvisNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()

        if (title.isBlank() && body.isBlank()) return

        val friendlyApp = when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") -> "Messages"
            pkg.contains("gm") || pkg.contains("mail") -> "Gmail"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("telegram") -> "Telegram"
            pkg.contains("dialer") || pkg.contains("telecom") -> "Phone"
            else -> pkg.substringAfterLast('.')
        }

        // Ignore ongoing/group summary noise
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        recentMessages.add(0, CapturedMessage(friendlyApp, title.ifBlank { friendlyApp }, body, System.currentTimeMillis()))
        while (recentMessages.size > 12) {
            recentMessages.removeAt(recentMessages.lastIndex)
        }
    }

    companion object {
        val recentMessages = CopyOnWriteArrayList<CapturedMessage>()

        fun buildSpokenSummary(userName: String): String {
            if (recentMessages.isEmpty()) {
                return "$userName, abhi koi naya unread message ya notification nahi aaya hai. Agar Notification Access off hai toh app settings se enable karein."
            }
            val top = recentMessages.take(3)
            val summary = top.joinToString(". ") { msg ->
                if (msg.text.isNotBlank()) {
                    "${msg.appName} par ${msg.sender} ka message hai: ${msg.text}"
                } else {
                    "${msg.appName} se ${msg.sender} ki notification hai"
                }
            }
            return "$userName, aapke latest ${top.size} messages hain: $summary."
        }
    }
}
