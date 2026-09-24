package app.lifeos.core.runtime.policy

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OwnerObservationPolicyTest {
    private val actor = OwnerActorId("owner")
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun matchingGrantAllowsObservationButDoesNotExpressEffectAuthority() = runTest {
        val repository = InMemoryOwnerObservationPolicyRepository()
        val ledger = OwnerObservationPolicyLedger(repository) { now }
        val grant = notificationGrant()

        ledger.grant(grant)

        val decision = ledger.evaluate(
            OwnerObservationRequest(
                actorId = actor,
                observationType = OwnerObservationType.NOTIFICATION,
                resource = "android-notification:example.app:key",
                scope = "notification-scope",
                sensorId = "android-notification-listener",
            )
        )

        val allowed = assertIs<OwnerObservationDecision.Allowed>(decision)
        assertEquals(grant.id, allowed.grantId)
    }

    @Test
    fun revokeIsDurableTombstoneForExactGrantIdentity() = runTest {
        val repository = InMemoryOwnerObservationPolicyRepository()
        val ledger = OwnerObservationPolicyLedger(repository) { now }
        val grant = notificationGrant()

        ledger.grant(grant)
        ledger.revoke(grant.id)

        assertEquals(
            OwnerObservationGrantHistoryState.REVOKED,
            ledger.historyState(grant.id),
        )
        assertIs<OwnerObservationDecision.Blocked>(
            ledger.evaluate(
                OwnerObservationRequest(
                    actorId = actor,
                    observationType = OwnerObservationType.NOTIFICATION,
                    resource = "android-notification:example.app:key",
                    scope = "notification-scope",
                    sensorId = "android-notification-listener",
                )
            )
        )
    }

    @Test
    fun platformResourceWithoutMatchingSensorGrantFailsClosed() = runTest {
        val repository = InMemoryOwnerObservationPolicyRepository()
        val ledger = OwnerObservationPolicyLedger(repository) { now }
        ledger.grant(notificationGrant())

        val decision = ledger.evaluate(
            OwnerObservationRequest(
                actorId = actor,
                observationType = OwnerObservationType.NOTIFICATION,
                resource = "android-notification:example.app:key",
                scope = "notification-scope",
                sensorId = "other-sensor",
            )
        )

        assertIs<OwnerObservationDecision.Blocked>(decision)
    }

    private fun notificationGrant() = OwnerObservationGrant.create(
        actorId = actor,
        observationType = OwnerObservationType.NOTIFICATION,
        resource = OwnerResourceSelector(
            OwnerResourceSelectorType.PREFIX,
            "android-notification:",
        ),
        scope = "notification-scope",
        sensorId = "android-notification-listener",
        validFrom = Instant.EPOCH,
    )

    private class InMemoryOwnerObservationPolicyRepository :
        OwnerObservationPolicyRepository {
        private val events = mutableListOf<OwnerObservationPolicyEvent>()

        override suspend fun loadReport() =
            OwnerObservationPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(
            expectedRevision: Long,
            event: OwnerObservationPolicyEvent,
        ): Boolean {
            if (events.size.toLong() != expectedRevision) return false
            events += event
            return true
        }
    }
}
