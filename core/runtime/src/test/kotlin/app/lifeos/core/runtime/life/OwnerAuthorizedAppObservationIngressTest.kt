package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerObservationGrant
import app.lifeos.core.runtime.policy.OwnerObservationPolicyEvent
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepository
import app.lifeos.core.runtime.policy.OwnerObservationPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerAuthorizedAppObservationIngressTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val actor = OwnerActorId("owner")
    private val descriptor = SensorDescriptor(
        sensorId = SensorId("calendar-provider"),
        sensorClass = SensorClass.CALENDAR,
        adapterVersion = "1",
        observationType = OwnerObservationType.CALENDAR,
        resourcePrefix = "calendar://",
        supportedSurfaces = setOf(ObservationSurfaceKind.CONTENT_PROVIDER),
    )

    @Test
    fun exactOwnerObservationGrantIsBoundAsProvenanceOnly() = runTest {
        val repository = InMemoryObservationPolicyRepository()
        val policy = OwnerObservationPolicyLedger(repository) { now }
        val grant = OwnerObservationGrant.create(
            actorId = actor,
            observationType = OwnerObservationType.CALENDAR,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                "calendar://personal/",
            ),
            scope = "personal-context",
            sensorId = descriptor.sensorId.value,
            validFrom = now.minusSeconds(60),
        )
        policy.grant(grant)

        val observation = observation("calendar://personal/event/1")
        val result = OwnerAuthorizedAppObservationIngress(
            observationPolicy = policy,
            actorId = actor,
            scope = "personal-context",
        ).authorize(
            descriptor = descriptor,
            batch = batch(observation),
        )

        assertEquals(1, result.authorized.size)
        assertTrue(result.blocked.isEmpty())
        assertEquals(grant.id.value, result.authorized.single().observationGrantId)
        assertEquals(observation.id, result.authorized.single().id)
        assertEquals(
            observation.sourceObservationFingerprint,
            result.authorized.single().sourceObservationFingerprint,
        )
        assertFalse(result.effectAuthority)
        assertFalse(result.ownerPolicyEffectAuthority)
    }

    @Test
    fun ungrantedResourceIsBlockedWithoutCopyingPayloadIntoDecision() = runTest {
        val repository = InMemoryObservationPolicyRepository()
        val policy = OwnerObservationPolicyLedger(repository) { now }
        policy.grant(
            OwnerObservationGrant.create(
                actorId = actor,
                observationType = OwnerObservationType.CALENDAR,
                resource = OwnerResourceSelector(
                    OwnerResourceSelectorType.PREFIX,
                    "calendar://work/",
                ),
                scope = "personal-context",
                sensorId = descriptor.sensorId.value,
                validFrom = now.minusSeconds(60),
            )
        )

        val observation = observation("calendar://personal/event/2")
        val result = OwnerAuthorizedAppObservationIngress(
            policy,
            actor,
            "personal-context",
        ).authorize(descriptor, batch(observation))

        assertTrue(result.authorized.isEmpty())
        assertEquals(observation.id, result.blocked.single().observationId)
        assertTrue(
            result.blocked.single().reasons.any {
                it.startsWith("observation-resource-not-granted:")
            }
        )
    }

    @Test
    fun revocationBlocksFutureIngress() = runTest {
        val repository = InMemoryObservationPolicyRepository()
        val policy = OwnerObservationPolicyLedger(repository) { now }
        val grant = OwnerObservationGrant.create(
            actorId = actor,
            observationType = OwnerObservationType.CALENDAR,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
            scope = "personal-context",
            sensorId = descriptor.sensorId.value,
            validFrom = now.minusSeconds(60),
        )
        policy.grant(grant)
        policy.revoke(grant.id)

        val result = OwnerAuthorizedAppObservationIngress(
            policy,
            actor,
            "personal-context",
        ).authorize(
            descriptor,
            batch(observation("calendar://personal/event/3")),
        )

        assertTrue(result.authorized.isEmpty())
        assertEquals(1, result.blocked.size)
    }

    @Test
    fun adapterCannotPreAuthorizeObservation() = runTest {
        val repository = InMemoryObservationPolicyRepository()
        val policy = OwnerObservationPolicyLedger(repository) { now }
        val forged = observation("calendar://personal/event/4")
            .authorizedBy("owner-observation-grant:" + "a".repeat(64))

        var rejected = false
        try {
            OwnerAuthorizedAppObservationIngress(
                policy,
                actor,
                "personal-context",
            ).authorize(descriptor, batch(forged))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun authorizedObservationCommitsExactlyOneOriginPhotonAndRetainsGrantProvenance() = runTest {
        val repository = InMemoryObservationPolicyRepository()
        val policy = OwnerObservationPolicyLedger(repository) { now }
        val grant = OwnerObservationGrant.create(
            actorId = actor,
            observationType = OwnerObservationType.CALENDAR,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
            scope = "personal-context",
            sensorId = descriptor.sensorId.value,
            validFrom = now.minusSeconds(60),
        )
        policy.grant(grant)

        val authorized = OwnerAuthorizedAppObservationIngress(
            policy,
            actor,
            "personal-context",
        ).authorize(
            descriptor,
            batch(observation("calendar://personal/event/5")),
        )

        val persisted = mutableListOf<Photon>()
        val receipt = AuthorizedObservationPhotonCommitter { photon ->
            persisted += photon
        }.commit(authorized)

        assertEquals(1, persisted.size)
        assertEquals(1, receipt.committed.size)
        assertTrue(receipt.blocked.isEmpty())
        assertEquals(authorized.authorized.single().id, receipt.committed.single().observationId)
        assertEquals(persisted.single().id, receipt.committed.single().photonId)
        assertEquals(grant.id.value, receipt.committed.single().observationGrantId)
        assertTrue("owner-authorized-observation" in persisted.single().tags)
        assertTrue("observation-grant:${grant.id.value}" in persisted.single().tags)
        assertFalse(receipt.effectAuthority)
    }

    @Test
    fun blockedObservationNeverReachesPhotonCommitCallback() = runTest {
        val repository = InMemoryObservationPolicyRepository()
        val policy = OwnerObservationPolicyLedger(repository) { now }
        val blocked = OwnerAuthorizedAppObservationIngress(
            policy,
            actor,
            "personal-context",
        ).authorize(
            descriptor,
            batch(observation("calendar://personal/event/6")),
        )

        var writes = 0
        val receipt = AuthorizedObservationPhotonCommitter {
            writes += 1
        }.commit(blocked)

        assertEquals(0, writes)
        assertTrue(receipt.committed.isEmpty())
        assertEquals(1, receipt.blocked.size)
    }

    private fun observation(resource: String) = InformationObservation(
        sourceId = descriptor.sensorId.value,
        sourceResource = resource,
        surface = ObservationSurfaceKind.CONTENT_PROVIDER,
        observedAt = now,
        sourceTimestamp = now,
        sourceRevision = "rev-1",
        mimeType = "application/vnd.lifeos.calendar+text",
        payload = "event=appointment",
        realization = RealizationDescriptor(
            representation = RepresentationLevel.ACTUAL,
            epistemicStatus = EpistemicStatus.OBSERVED,
            temporalStatus = TemporalStatus.CURRENT,
            controlStatus = ControlStatus.PASSIVE,
        ),
        authority = ObservationAuthorityClass.PLATFORM_PROVIDER,
        privacy = ObservationPrivacyClass.PERSONAL,
        confidence = 1.0,
    )

    private fun batch(
        observation: InformationObservation,
    ) = AppObservationBatch.create(
        sensorId = descriptor.sensorId,
        observations = listOf(observation),
        nextCursor = AppSensorCursor(
            sensorId = descriptor.sensorId,
            revision = 1L,
        ),
        exhausted = true,
    )

    private class InMemoryObservationPolicyRepository :
        OwnerObservationPolicyRepository {
        private val events = mutableListOf<OwnerObservationPolicyEvent>()

        override suspend fun loadReport() =
            OwnerObservationPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(
            expectedRevision: Long,
            event: OwnerObservationPolicyEvent,
        ): Boolean {
            val head = events.maxOfOrNull { it.revision } ?: 0L
            if (head != expectedRevision) return false
            require(event.revision == head + 1L)
            events += event
            return true
        }
    }
}
