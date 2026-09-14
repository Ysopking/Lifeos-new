package app.lifeos.next

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.next.kernel.LiveNotificationPhotonIngress
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owner-enabled live Android context stream. Only user-visible notification text is projected into
 * canonical Photons; no app databases, accessibility scraping or parallel message store is used.
 */
class LiveNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications
            ?.sortedWith(compareBy<StatusBarNotification> { it.postTime }.thenBy { it.key })
            ?.forEach(::submit)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::submit)
    }

    private fun submit(sbn: StatusBarNotification) {
        val photon = sbn.toPhoton() ?: return
        scope.launch {
            LiveNotificationPhotonIngress.ingest(photon)
        }
    }

    private fun StatusBarNotification.toPhoton(): Photon? {
        if (packageName == applicationContext.packageName) return null
        val notification = notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.visibility == Notification.VISIBILITY_SECRET) return null

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.clean().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.clean().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.clean().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.clean().orEmpty()
        val body = bigText.ifBlank { text }
        if (title.isBlank() && body.isBlank() && conversation.isBlank()) return null

        val category = notification.category.orEmpty()
        val postedAt = Instant.ofEpochMilli(postTime.coerceAtLeast(0L))
        val fingerprint = StableCognitiveIds.fingerprint(
            "android-live-notification/v1",
            key,
            packageName,
            postTime.toString(),
            category,
            title,
            body,
            conversation,
        )
        val isMessage = category == Notification.CATEGORY_MESSAGE || conversation.isNotBlank()
        return Photon(
            id = PhotonId("android-notification-$fingerprint"),
            content = buildString {
                appendLine("package=$packageName")
                appendLine("category=$category")
                appendLine("title=$title")
                appendLine("conversation=$conversation")
                appendLine("text=$body")
                append("posted_at=${postedAt}")
            },
            mimeType = "application/vnd.lifeos.android-notification+text",
            confidence = 1.0,
            provenance = Provenance(
                source = "android-notification-listener",
                actor = packageName,
                createdAt = postedAt,
            ),
            tags = buildSet {
                add("notification")
                add("live-context")
                add("app:$packageName")
                if (category.isNotBlank()) add("notification-category:$category")
                if (isMessage) add("message")
                if (conversation.isNotBlank()) add("conversation:$conversation")
            },
        )
    }

    private fun String.clean(): String = replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_TEXT_LENGTH)

    private companion object {
        const val MAX_TEXT_LENGTH = 8_192
    }
}
