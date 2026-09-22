package app.lifeos.next

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.android.NotificationActionDescriptor
import app.lifeos.core.runtime.android.NotificationIdentity
import app.lifeos.next.kernel.LiveNotificationPhotonIngress
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owner-enabled live Android context stream.
 *
 * Durable Photons contain descriptive user-visible notification data only. B409 keeps executable
 * notification action handles in a bounded process-local registry that is cleared across listener
 * lifecycle boundaries and can never be reconstructed from a persisted Photon.
 */
class LiveNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        LiveNotificationActionRegistry.clear()
        activeNotifications
            ?.sortedWith(compareBy<StatusBarNotification> { it.postTime }.thenBy { it.key })
            ?.forEach(::submit)
    }

    override fun onListenerDisconnected() {
        LiveNotificationActionRegistry.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::submit)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let(LiveNotificationActionRegistry::remove)
    }

    private fun submit(sbn: StatusBarNotification) {
        val projection = sbn.toProjection()
        if (projection == null) {
            LiveNotificationActionRegistry.remove(sbn.key)
            return
        }

        projection.identity?.let { identity ->
            LiveNotificationActionRegistry.replace(
                identity = identity,
                actions = projection.handles,
                dismiss = { cancelNotification(identity.notificationKey) },
            )
        } ?: LiveNotificationActionRegistry.remove(sbn.key)

        scope.launch {
            LiveNotificationPhotonIngress.ingest(projection.photon)
        }
    }

    private fun StatusBarNotification.toProjection(): NotificationProjection? {
        if (packageName == applicationContext.packageName) return null
        val notification = notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.visibility == Notification.VISIBILITY_SECRET) return null

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.clean().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.clean().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.clean().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            ?.clean()
            .orEmpty()
        val body = bigText.ifBlank { text }
        if (title.isBlank() && body.isBlank() && conversation.isBlank()) return null

        val actionLabels = notification.actions
            .orEmpty()
            .mapNotNull { action ->
                action.title?.toString()
                    ?.clean()
                    ?.take(MAX_ACTION_LABEL_LENGTH)
                    ?.takeIf(String::isNotBlank)
            }
            .take(MAX_ACTIONS)

        val category = notification.category.orEmpty()
        val postedMillis = postTime.coerceAtLeast(0L)
        val postedAt = Instant.ofEpochMilli(postedMillis)
        val fingerprint = StableCognitiveIds.fingerprint(
            "android-live-notification/v2",
            key,
            packageName,
            postedMillis.toString(),
            category,
            title,
            body,
            conversation,
            actionLabels.joinToString("\u001f"),
        )
        val isMessage = category == Notification.CATEGORY_MESSAGE || conversation.isNotBlank()
        val photon = Photon(
            id = PhotonId("android-notification-$fingerprint"),
            content = buildString {
                appendLine("package=$packageName")
                appendLine("category=$category")
                appendLine("title=$title")
                appendLine("conversation=$conversation")
                appendLine("text=$body")
                appendLine("action_count=${actionLabels.size}")
                appendLine("action_labels=${actionLabels.joinToString(" || ")}")
                append("posted_at=$postedAt")
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
                if (actionLabels.isNotEmpty()) add("notification-actions")
            },
        )

        val identity = runCatching {
            NotificationIdentity(
                notificationKey = key,
                packageName = packageName,
                postedAtMillis = postedMillis,
                observationFingerprint = fingerprint,
            )
        }.getOrNull()

        val handles = if (identity == null) {
            emptyList()
        } else {
            notification.actions
                .orEmpty()
                .asSequence()
                .mapIndexedNotNull { index, action ->
                    if (index >= MAX_ACTIONS) return@mapIndexedNotNull null
                    val pendingIntent = action.actionIntent ?: return@mapIndexedNotNull null
                    val label = action.title
                        ?.toString()
                        ?.clean()
                        ?.take(MAX_ACTION_LABEL_LENGTH)
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapIndexedNotNull null
                    val descriptor = NotificationActionDescriptor.create(
                        identity = identity,
                        index = index,
                        label = label,
                    )
                    LiveNotificationActionHandle(
                        descriptor = descriptor,
                        invoke = { pendingIntent.send() },
                    )
                }
                .toList()
        }

        return NotificationProjection(
            photon = photon,
            identity = identity,
            handles = handles,
        )
    }

    private fun String.clean(): String = replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_TEXT_LENGTH)

    private data class NotificationProjection(
        val photon: Photon,
        val identity: NotificationIdentity?,
        val handles: List<LiveNotificationActionHandle>,
    )

    private companion object {
        const val MAX_TEXT_LENGTH = 8_192
        const val MAX_ACTION_LABEL_LENGTH = 256
        const val MAX_ACTIONS = 16
    }
}
