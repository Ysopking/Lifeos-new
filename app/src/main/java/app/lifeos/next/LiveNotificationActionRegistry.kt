package app.lifeos.next

import app.lifeos.core.runtime.android.NotificationActionDescriptor
import app.lifeos.core.runtime.android.NotificationActionHost
import app.lifeos.core.runtime.android.NotificationActionReceipt
import app.lifeos.core.runtime.android.NotificationActionRequest
import app.lifeos.core.runtime.android.NotificationDismissRequest
import app.lifeos.core.runtime.android.NotificationHandleSnapshot
import app.lifeos.core.runtime.android.NotificationIdentity

internal data class LiveNotificationActionHandle(
    val descriptor: NotificationActionDescriptor,
    val invoke: () -> Unit,
)

/**
 * B409 process-local executable notification registry.
 *
 * No PendingIntent or dismiss callback is serialized. The listener replaces registrations whenever
 * a notification changes and clears all entries across listener reconnect/disconnect boundaries.
 */
internal object LiveNotificationActionRegistry : NotificationActionHost {
    private data class Entry(
        val identity: NotificationIdentity,
        val snapshot: NotificationHandleSnapshot,
        val actions: Map<String, LiveNotificationActionHandle>,
        val dismiss: () -> Unit,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    fun replace(
        identity: NotificationIdentity,
        actions: List<LiveNotificationActionHandle>,
        dismiss: () -> Unit,
    ) {
        require(actions.size <= MAX_ACTIONS_PER_NOTIFICATION)
        val canonical = actions.sortedBy { it.descriptor.index }
        require(canonical.map { it.descriptor.index }.distinct().size == canonical.size)
        canonical.forEach { handle ->
            require(
                NotificationActionDescriptor.create(
                    identity = identity,
                    index = handle.descriptor.index,
                    label = handle.descriptor.label,
                ) == handle.descriptor
            ) {
                "Notification action descriptor does not match live identity"
            }
        }

        val snapshot = NotificationHandleSnapshot.create(
            identity = identity,
            actions = canonical.map { it.descriptor },
        )
        val entry = Entry(
            identity = identity,
            snapshot = snapshot,
            actions = canonical.associateBy { it.descriptor.fingerprint },
            dismiss = dismiss,
        )

        synchronized(lock) {
            entries.remove(identity.notificationKey)
            entries[identity.notificationKey] = entry
            while (entries.size > MAX_ACTIVE_NOTIFICATIONS) {
                val eldest = entries.entries.firstOrNull()?.key ?: break
                entries.remove(eldest)
            }
        }
    }

    fun remove(notificationKey: String) {
        synchronized(lock) {
            entries.remove(notificationKey)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    override suspend fun inspect(
        identity: NotificationIdentity,
    ): NotificationHandleSnapshot? = synchronized(lock) {
        entries[identity.notificationKey]
            ?.takeIf { it.identity == identity }
            ?.snapshot
    }

    override suspend fun invoke(
        request: NotificationActionRequest,
    ): NotificationActionReceipt {
        val callback = synchronized(lock) {
            val entry = entries[request.identity.notificationKey]
                ?.takeIf { it.identity == request.identity }
                ?: error("notification-handle-stale")
            val handle = entry.actions[request.action.fingerprint]
                ?: error("notification-action-stale")
            require(handle.descriptor == request.action) {
                "notification-action-descriptor-mismatch"
            }
            handle.invoke
        }
        callback()
        return NotificationActionReceipt.invoked(request)
    }

    override suspend fun dismiss(
        request: NotificationDismissRequest,
    ): NotificationActionReceipt {
        val callback = synchronized(lock) {
            val entry = entries[request.identity.notificationKey]
                ?.takeIf { it.identity == request.identity }
                ?: error("notification-handle-stale")
            entry.dismiss
        }
        callback()
        // The receipt means the cancel request was exposed, not that external state is already gone.
        remove(request.identity.notificationKey)
        return NotificationActionReceipt.dismissed(request)
    }

    internal fun sizeForTests(): Int = synchronized(lock) { entries.size }

    private const val MAX_ACTIVE_NOTIFICATIONS = 256
    private const val MAX_ACTIONS_PER_NOTIFICATION = 16
}
