package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldRunId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.ThoughtMatrix
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThoughtMatrixFieldCutoverLifecycleTest {
    private val at = Instant.parse("2026-09-19T20:00:00Z")

    @Test
    fun thirtyTwoDeterministicReplayCasesBecomeEligibleAndExactFingerprintActivates() = runTest {
        val ledger = BoundedFieldShadowValidationLedger(capacity = 64)
        val states = MemoryStateRepository()
        val validator = FieldShadowValidator(
            policy = FieldShadowValidationPolicy(
                selectedDomains = setOf(ThoughtMatrix.FIELD_DOMAIN_ID),
                maximumConfidenceDeltaByDomain =
                    mapOf(ThoughtMatrix.FIELD_DOMAIN_ID to 1.0),
            ),
            now = { at },
        )
        val authority = FieldCutoverAuthority(
            ledger = ledger,
            states = states,
            validator = validator,
        )
        val replay = ThoughtMatrixFieldCutoverReplayCoordinator(
            ledger = ledger,
            validator = validator,
            authority = authority,
        )
        val photons = List(32) { index ->
            photon(
                id = "replay-" + index,
                confidence = index.toDouble() / 31.0,
                second = index.toLong(),
            )
        }

        val summary = replay.replayAndAssess(
            photons = photons,
            at = at,
            provenance = "test-replay",
        )

        assertEquals(32, summary.caseCount)
        assertEquals(32, summary.newlyRecordedCount)
        assertEquals(
            FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN,
            summary.assessment.report.recommendation,
        )
        assertEquals(FieldCutoverMode.ELIGIBLE, summary.assessment.state.mode)

        val lifecycle = FieldCutoverLifecycleCoordinator(
            authority = authority,
            selectedDomains = setOf(ThoughtMatrix.FIELD_DOMAIN_ID),
        )
        val activated = lifecycle.activate(
            domainId = ThoughtMatrix.FIELD_DOMAIN_ID,
            expectedEvidenceFingerprint = summary.assessment.evidenceFingerprint,
            at = at.plusSeconds(1),
            provenance = "test-activation",
        )
        assertEquals(FieldCutoverMode.AUTHORITATIVE, activated.mode)
        assertEquals(summary.assessment.evidenceFingerprint, activated.evidenceFingerprint)
    }

    @Test
    fun replayRetryIsIdempotentAndDoesNotAdvanceEligibleStateRevision() = runTest {
        val ledger = BoundedFieldShadowValidationLedger(capacity = 64)
        val states = MemoryStateRepository()
        val validator = FieldShadowValidator(
            policy = FieldShadowValidationPolicy(
                selectedDomains = setOf(ThoughtMatrix.FIELD_DOMAIN_ID),
                maximumConfidenceDeltaByDomain =
                    mapOf(ThoughtMatrix.FIELD_DOMAIN_ID to 1.0),
            ),
            now = { at },
        )
        val authority = FieldCutoverAuthority(ledger, states, validator)
        val replay = ThoughtMatrixFieldCutoverReplayCoordinator(
            ledger = ledger,
            validator = validator,
            authority = authority,
        )
        val photons = List(32) { index ->
            photon("retry-" + index, confidence = 0.8, second = index.toLong())
        }

        val first = replay.replayAndAssess(photons, at, "first")
        val retry = replay.replayAndAssess(photons.reversed(), at.plusSeconds(10), "retry")

        assertEquals(0, retry.newlyRecordedCount)
        assertEquals(first.assessment.evidenceFingerprint, retry.assessment.evidenceFingerprint)
        assertEquals(first.assessment.state.revision, retry.assessment.state.revision)
        assertEquals(FieldCutoverMode.ELIGIBLE, retry.assessment.state.mode)
    }

    @Test
    fun activationFailsClosedWhenEvidenceFingerprintDoesNotMatchFreshAssessment() = runTest {
        val ledger = BoundedFieldShadowValidationLedger(capacity = 64)
        val states = MemoryStateRepository()
        val validator = FieldShadowValidator(
            policy = FieldShadowValidationPolicy(
                selectedDomains = setOf(ThoughtMatrix.FIELD_DOMAIN_ID),
                maximumConfidenceDeltaByDomain =
                    mapOf(ThoughtMatrix.FIELD_DOMAIN_ID to 1.0),
            ),
            now = { at },
        )
        val authority = FieldCutoverAuthority(ledger, states, validator)
        val replay = ThoughtMatrixFieldCutoverReplayCoordinator(
            ledger = ledger,
            validator = validator,
            authority = authority,
        )
        replay.replayAndAssess(
            photons = List(32) { index ->
                photon("stale-" + index, confidence = 1.0, second = index.toLong())
            },
            at = at,
            provenance = "test-replay",
        )
        val lifecycle = FieldCutoverLifecycleCoordinator(
            authority,
            setOf(ThoughtMatrix.FIELD_DOMAIN_ID),
        )

        assertFailsWith<IllegalArgumentException> {
            lifecycle.activate(
                domainId = ThoughtMatrix.FIELD_DOMAIN_ID,
                expectedEvidenceFingerprint = "0".repeat(64),
                at = at.plusSeconds(1),
                provenance = "stale-activation",
            )
        }
    }

    @Test
    fun authoritativeProcessorUpdatesCompatibilityReadModelOnlyAfterUniversalCompletion() = runTest {
        val matrix = ThoughtMatrix()
        val source = photon("authoritative", confidence = 0.4, second = 0)
        val successful = ThoughtMatrixAuthoritativeFieldProcessor(
            matrix = matrix,
            universal = FieldShadowProcessor { photon ->
                FieldShadowExecution(
                    state = FieldShadowState.COMPLETED,
                    domainId = ThoughtMatrix.FIELD_DOMAIN_ID,
                    runId = FieldRunId("run-authoritative"),
                    snapshotId = FieldSnapshotId("snapshot-authoritative"),
                    convergenceStatus = ConvergenceStatus.CONVERGED,
                    sourcePhotonId = photon.id,
                    sourceRevision = photon.revision,
                    sourceFingerprint = runtimePhotonFingerprint(photon),
                )
            },
        )

        successful.process(source)

        assertTrue(source.id in matrix.state.value.nodes)

        val blockedMatrix = ThoughtMatrix()
        val blocked = ThoughtMatrixAuthoritativeFieldProcessor(
            matrix = blockedMatrix,
            universal = FieldShadowProcessor { photon ->
                FieldShadowExecution.blocked(
                    domainId = ThoughtMatrix.FIELD_DOMAIN_ID,
                    message = "blocked",
                    source = photon,
                )
            },
        )
        blocked.process(source)
        assertTrue(blockedMatrix.state.value.nodes.isEmpty())
    }

    private fun photon(
        id: String,
        confidence: Double,
        second: Long,
    ): Photon = Photon(
        id = PhotonId(id),
        revision = 1L,
        content = "content-" + id,
        semanticMass = 1.0,
        energy = 0.2,
        confidence = confidence,
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = at.plusSeconds(second),
        ),
        tags = setOf("chat"),
    )

    private class MemoryStateRepository : FieldCutoverStateRepository {
        private val states = linkedMapOf<String, FieldCutoverState>()

        override suspend fun load(
            domainId: app.lifeos.core.field.FieldDomainId,
        ): FieldCutoverState? = states[domainId.value]

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            state: FieldCutoverState,
        ): Boolean {
            val current = states[state.domainId.value]
            if (current?.revision != expectedRevision) return false
            states[state.domainId.value] = state
            return true
        }
    }
}
