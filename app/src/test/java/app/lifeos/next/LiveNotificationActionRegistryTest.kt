package app.lifeos.next

import app.lifeos.core.runtime.android.NotificationActionDescriptor
import app.lifeos.core.runtime.android.NotificationActionReceipt
import app.lifeos.core.runtime.android.NotificationActionRequest
import app.lifeos.core.runtime.android.NotificationDismissRequest
import app.lifeos.core.runtime.android.NotificationIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LiveNotificationActionRegistryTest {
    @AfterTest
    fun tearDown() {
        LiveNotificationActionRegistry.clear()
    }

    @Test
    fun update_replaces_old_identity_and_invalidates_stale_handle() = runTest {
        val first = identity("shared-key", posted = 1L, fingerprintChar = 'a')
        val second = identity("shared-key", posted = 2L, fingerprintChar = 'b')
        val firstAction = NotificationActionDescriptor.create(first, 0, "Reply")
        val secondAction = NotificationActionDescriptor.create(second, 0, "Reply")
        var invocations = 0

        LiveNotificationActionRegistry.replace(
            first,
            listOf(LiveNotificationActionHandle(firstAction) { invocations += 1 }),
        ) {}
        LiveNotificationActionRegistry.replace(
            second,
            listOf(LiveNotificationActionHandle(secondAction) { invocations += 10 }),
        ) {}

        assertNull(LiveNotificationActionRegistry.inspect(first))
        assertEquals(second, LiveNotificationActionRegistry.inspect(second)?.identity)
        assertFailsWith<IllegalStateException> {
            LiveNotificationActionRegistry.invoke(
                NotificationActionRequest(first, firstAction)
            )
        }

        val receipt = LiveNotificationActionRegistry.invoke(
            NotificationActionRequest(second, secondAction)
        )
        assertEquals(NotificationActionReceipt.invoked(
            NotificationActionRequest(second, secondAction)
        ), receipt)
        assertEquals(10, invocations)
    }

    @Test
    fun dismiss_is_exact_and_removes_process_local_entry() = runTest {
        val identity = identity("dismiss-key", 5L, 'c')
        var dismissCalls = 0
        LiveNotificationActionRegistry.replace(
            identity = identity,
            actions = emptyList(),
            dismiss = { dismissCalls += 1 },
        )

        LiveNotificationActionRegistry.dismiss(NotificationDismissRequest(identity))

        assertEquals(1, dismissCalls)
        assertNull(LiveNotificationActionRegistry.inspect(identity))
    }

    @Test
    fun registry_is_bounded_and_clearable() = runTest {
        repeat(300) { index ->
            val identity = NotificationIdentity(
                notificationKey = "key-$index",
                packageName = "app.example",
                postedAtMillis = index.toLong(),
                observationFingerprint = index.toString(16).padStart(64, '0').takeLast(64),
            )
            LiveNotificationActionRegistry.replace(
                identity = identity,
                actions = emptyList(),
                dismiss = {},
            )
        }

        assertEquals(256, LiveNotificationActionRegistry.sizeForTests())

        LiveNotificationActionRegistry.clear()

        assertEquals(0, LiveNotificationActionRegistry.sizeForTests())
    }

    private fun identity(
        key: String,
        posted: Long,
        fingerprintChar: Char,
    ): NotificationIdentity = NotificationIdentity(
        notificationKey = key,
        packageName = "app.example",
        postedAtMillis = posted,
        observationFingerprint = fingerprintChar.toString().repeat(64),
    )
}
