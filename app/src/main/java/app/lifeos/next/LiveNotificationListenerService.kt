package app.lifeos.next

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.model.source.SourceConversationMetadata
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.runtime.livedata.LiveDataAccountKey
import app.lifeos.core.runtime.livedata.LiveDataAccountObservation
import app.lifeos.core.runtime.livedata.LiveDataCapability
import app.lifeos.core.runtime.livedata.LiveDataConnectorId
import app.lifeos.core.runtime.livedata.LiveDataDelta
import app.lifeos.core.runtime.livedata.LiveDataDeltaOperation
import app.lifeos.core.runtime.livedata.LiveDataPermission
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import app.lifeos.core.runtime.livedata.LiveDataStreamKind
import app.lifeos.core.runtime.livedata.canonicalLiveDataMetadata
import app.lifeos.next.kernel.PushLiveDataIngress
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owner-enabled live Android context stream. It projects only user-visible notification data into
 * the canonical LiveDataHub path; no foreign app database or accessibility store is inspected.
 */
class LiveNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var accountObservation: LiveDataAccountObservation? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        val observation = createAccountObservation(Instant.now())
        accountObservation = observation
        activeNotifications
            ?.sortedWith(compareBy<StatusBarNotification> { it.postTime }.thenBy { it.key })
            ?.forEach { submit(it, observation) }
    }

    override fun onListenerDisconnected() {
        accountObservation = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { submit(it, currentAccountObservation()) }
    }

    private fun submit(
        sbn: StatusBarNotification,
        observation: LiveDataAccountObservation,
    ) {
        val delta = sbn.toLiveDataDelta(observation) ?: return
        scope.launch {
            PushLiveDataIngress.ingest(observation, delta)
        }
    }

    private fun currentAccountObservation(): LiveDataAccountObservation =
        accountObservation ?: synchronized(this) {
            accountObservation ?: createAccountObservation(Instant.now()).also {
                accountObservation = it
            }
        }

    private fun createAccountObservation(observedAt: Instant): LiveDataAccountObservation =
        LiveDataAccountObservation(
            connectorId = CONNECTOR_ID,
            accountKey = ACCOUNT_KEY,
            capabilities = setOf(LiveDataCapability.MESSAGE_DELTAS),
            permissions = mapOf(
                LiveDataPermission.READ_MESSAGES to LiveDataPermissionState.GRANTED,
                LiveDataPermission.READ_CALENDAR to LiveDataPermissionState.UNAVAILABLE,
                LiveDataPermission.READ_FILES to LiveDataPermissionState.UNAVAILABLE,
            ),
            observedAt = observedAt,
            sourceCursor = "notification-listener-connected",
        )

    private fun StatusBarNotification.toLiveDataDelta(
        observation: LiveDataAccountObservation,
    ): LiveDataDelta? {
        if (packageName == applicationContext.packageName) return null
        val notification = notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.visibility == Notification.VISIBILITY_SECRET) return null

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.clean().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.clean().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.clean().orEmpty()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            ?.clean()
            .orEmpty()
        val body = bigText.ifBlank { text }
        if (title.isBlank() && body.isBlank() && conversationTitle.isBlank()) return null

        val category = notification.category.orEmpty()
        val postedAt = Instant.ofEpochMilli(postTime.coerceAtLeast(0L))
        val observedAt = maxOf(postedAt, Instant.now())
        val version = StableCognitiveIds.fingerprint(
            "android-live-notification/v2",
            key,
            packageName,
            postTime.toString(),
            category,
            title,
            body,
            conversationTitle,
        )
        val shortcutId = notification.shortcutId?.clean()?.takeIf { it.isNotBlank() }
        val baseMetadata = canonicalLiveDataMetadata(
            connectorId = observation.connectorId,
            accountKey = observation.accountKey,
            kind = LiveDataStreamKind.MESSAGE,
            externalId = key,
            externalVersion = version,
            occurredAt = postedAt,
            observedAt = observedAt,
            mimeType = NOTIFICATION_MIME,
            privacyZone = SourcePrivacyZone.SENSITIVE,
        )
        val metadata = baseMetadata.copy(
            conversation = shortcutId?.let {
                SourceConversationMetadata(
                    conversationId = it,
                    threadId = it,
                )
            },
            technical = baseMetadata.technical.copy(
                producer = packageName,
                attributes = baseMetadata.technical.attributes + mapOf(
                    "android:notification-category" to category,
                    "android:notification-package" to packageName,
                    "android:notification-shortcut" to shortcutId.orEmpty(),
                ),
            ),
        )

        return LiveDataDelta(
            connectorId = observation.connectorId,
            accountKey = observation.accountKey,
            kind = LiveDataStreamKind.MESSAGE,
            externalId = key,
            externalVersion = version,
            operation = LiveDataDeltaOperation.UPSERT,
            occurredAt = postedAt,
            observedAt = observedAt,
            payload = buildString {
                appendLine("package=$packageName")
                appendLine("category=$category")
                appendLine("title=$title")
                appendLine("conversation=$conversationTitle")
                append("text=$body")
            },
            mimeType = NOTIFICATION_MIME,
            confidence = 1.0,
            metadata = metadata,
        )
    }

    private fun String.clean(): String = replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_TEXT_LENGTH)

    private companion object {
        val CONNECTOR_ID = LiveDataConnectorId("android-notifications")
        val ACCOUNT_KEY = LiveDataAccountKey("device-notification-listener")
        const val NOTIFICATION_MIME = "application/vnd.lifeos.android-notification+text"
        const val MAX_TEXT_LENGTH = 8_192
    }
}
