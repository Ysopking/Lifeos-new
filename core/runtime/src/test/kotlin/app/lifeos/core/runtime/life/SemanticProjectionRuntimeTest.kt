package app.lifeos.core.runtime.life

import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.runtime.world.StateDimensionId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticProjectionRuntimeTest {
    private val observedAt = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun projectedNotificationStaysObservationEvidenceWithSourceAuthority() {
        val observation = notification()
        val photon = sourcePhoton(observation)
        val runtime = SemanticProjectionRuntime(
            listOf(
                projector(
                    id = "finance",
                    candidate = candidate(
                        kind = EvidenceKind.OBSERVATION,
                        dimension = "finance.possible-charge",
                    ),
                )
            )
        )

        val result = runtime.project(observation, photon).single()

        assertEquals(SourceAuthority.DOCUMENTED, result.evidence.single().authority)
        assertEquals(EvidenceKind.OBSERVATION, result.evidence.single().kind)
        assertEquals(
            setOf(StateDimensionId("finance.possible-charge")),
            result.touchedStateDimensions,
        )
        assertFalse(result.directWorldStateMutationAllowed)
    }

    @Test
    fun projectedNotificationCannotBecomeConfirmedTransactionEvidence() {
        val observation = notification()
        val photon = sourcePhoton(observation)
        val runtime = SemanticProjectionRuntime(
            listOf(
                projector(
                    id = "finance",
                    candidate = candidate(
                        kind = EvidenceKind.TRANSACTION,
                        dimension = "finance.transaction",
                    ),
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.project(observation, photon)
        }
    }

    @Test
    fun exactObservationPhotonBindingIsMandatory() {
        val observation = notification()
        val other = notification(resource = "android-notification:other:key")
        val wrongPhoton = sourcePhoton(other)
        val runtime = SemanticProjectionRuntime(
            listOf(
                projector(
                    id = "finance",
                    candidate = candidate(
                        kind = EvidenceKind.OBSERVATION,
                        dimension = "finance.possible-charge",
                    ),
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.project(observation, wrongPhoton)
        }
    }

    @Test
    fun projectorOrderAndCandidateOrderAreDeterministic() {
        val observation = notification()
        val photon = sourcePhoton(observation)
        val a = object : SemanticObservationProjector {
            override val projectorId = "a"
            override val domainId = FieldDomainId("finance")
            override fun supports(observation: InformationObservation) = true
            override fun project(
                observation: InformationObservation,
            ) = listOf(
                candidate(EvidenceKind.OBSERVATION, "z"),
                candidate(EvidenceKind.OBSERVATION, "a"),
            )
        }
        val b = projector(
            id = "b",
            candidate = candidate(
                kind = EvidenceKind.OBSERVATION,
                dimension = "m",
            ),
        )

        val first = SemanticProjectionRuntime(listOf(b, a)).project(observation, photon)
        val second = SemanticProjectionRuntime(listOf(a, b)).project(observation, photon)

        assertEquals(first, second)
        assertEquals(listOf("a", "b"), first.map { it.projectorId })
        assertTrue(
            first.first().evidence.first().semanticKey.startsWith("a:")
        )
    }

    private fun notification(
        resource: String = "android-notification:bank:key",
    ) = InformationObservation(
        sourceId = "android-notification-listener",
        sourceResource = resource,
        surface = ObservationSurfaceKind.NOTIFICATION,
        observedAt = observedAt,
        sourceTimestamp = observedAt,
        sourceRevision = "source-revision",
        mimeType = "text/plain",
        payload = "Netflix 17.99 EUR",
        realization = RealizationDescriptor(
            representation = RepresentationLevel.PROJECTED,
            epistemicStatus = EpistemicStatus.OBSERVED,
            temporalStatus = TemporalStatus.CURRENT,
            controlStatus = ControlStatus.PASSIVE,
        ),
        authority = ObservationAuthorityClass.PLATFORM_NOTIFICATION,
        privacy = ObservationPrivacyClass.PERSONAL,
        confidence = 0.9,
    )

    private fun sourcePhoton(
        observation: InformationObservation,
    ) = PerceptionFusionEngine()
        .fuse(listOf(observation.toPerceptionSignal()))
        .photons
        .single()

    private fun candidate(
        kind: EvidenceKind,
        dimension: String,
    ) = SemanticEvidenceCandidate(
        stateDimension = StateDimensionId(dimension),
        semanticKey = "candidate",
        kind = kind,
        confidence = 0.8,
        reliability = EvidenceReliability(
            score = 0.7,
            reason = "test",
        ),
        payload = EvidencePayload(
            type = "finance-candidate",
            values = mapOf("value" to dimension),
        ),
        explanation = "test semantic candidate",
    )

    private fun projector(
        id: String,
        candidate: SemanticEvidenceCandidate,
    ) = object : SemanticObservationProjector {
        override val projectorId = id
        override val domainId = FieldDomainId("finance")
        override fun supports(observation: InformationObservation) = true
        override fun project(
            observation: InformationObservation,
        ) = listOf(candidate)
    }
}
