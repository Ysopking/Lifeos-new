package app.lifeos.core.runtime.hardening

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class V17HardeningEvidenceTest {
    @Test
    fun exactlyOnceEvidenceRejectsDuplicateProductiveEffects() {
        val evidence = sample().seal().copy(duplicateEffectCount = 1)
        assertFailsWith<IllegalArgumentException> {
            V17AcceptanceGate.requirePass(evidence)
        }
        assertTrue(
            V17EvidenceFailureCode.DUPLICATE_PRODUCTIVE_EFFECTS in
                V17AcceptanceGate.verify(evidence).failures
        )
    }

    @Test
    fun completeJourneyRequiresTraceResourceAndOutcomeEvidence() {
        val evidence = sample().copy(
            traceIds = listOf("decision-trace:abc"),
            resourceIds = listOf("resource-budget-reservation:abc"),
            outcomeIds = listOf("outcome:abc"),
        ).seal()
        assertTrue(
            V17AcceptanceGate.requireCompleteJourneyEvidence(
                V17Journey.GOAL_TO_EXPLANATION,
                evidence,
            ).passesExactlyOnce
        )
    }

    @Test
    fun unsealedEvidenceFailsClosed() {
        val verification = V17AcceptanceGate.verify(sample())
        assertTrue(V17EvidenceFailureCode.MISSING_INTEGRITY in verification.failures)
        assertFailsWith<IllegalArgumentException> {
            V17AcceptanceGate.requirePass(sample())
        }
    }

    @Test
    fun deterministicReplayProducesSameIdentityAndPersistenceFingerprint() {
        val parentA = "1".repeat(64)
        val parentB = "2".repeat(64)
        val first = sample().copy(
            traceIds = listOf("trace:b", "trace:a"),
            policyIds = listOf("policy:b", "policy:a"),
            resourceIds = listOf("resource:b", "resource:a"),
            outcomeIds = listOf("outcome:b", "outcome:a"),
            parentEvidenceSha256s = listOf(parentB, parentA),
        ).seal()
        val replay = sample().copy(
            traceIds = listOf("trace:a", "trace:b"),
            policyIds = listOf("policy:a", "policy:b"),
            resourceIds = listOf("resource:a", "resource:b"),
            outcomeIds = listOf("outcome:a", "outcome:b"),
            parentEvidenceSha256s = listOf(parentA, parentB),
        ).seal()

        assertEquals(first.integrity, replay.integrity)
        val firstPersisted = V17PersistedAcceptanceEvidence.from(first)
        val replayPersisted = V17PersistedAcceptanceEvidence.from(replay)
        assertEquals(firstPersisted.persistenceId, replayPersisted.persistenceId)
        assertEquals(firstPersisted.payloadSha256, replayPersisted.payloadSha256)
        assertTrue(V17AcceptanceGate.verifyPersisted(firstPersisted).passed)
        assertTrue(V17AcceptanceGate.verifyPersisted(replayPersisted).passed)
    }

    @Test
    fun tamperedContentFailsClosed() {
        val sealed = sample().seal()
        val tampered = sealed.copy(observedDurableState = "COMPLETED")
        val verification = V17AcceptanceGate.verify(tampered)

        assertTrue(V17EvidenceFailureCode.CONTENT_HASH_MISMATCH in verification.failures)
        assertTrue(V17EvidenceFailureCode.RECORD_HASH_MISMATCH in verification.failures)
    }

    @Test
    fun tamperedIdentityFailsClosed() {
        val sealed = sample().seal()
        val tampered = sealed.copy(runId = "run-2")
        val verification = V17AcceptanceGate.verify(tampered)

        assertTrue(V17EvidenceFailureCode.EVIDENCE_ID_MISMATCH in verification.failures)
        assertTrue(V17EvidenceFailureCode.IDENTITY_HASH_MISMATCH in verification.failures)
    }

    @Test
    fun tamperedProvenanceFailsClosed() {
        val sealed = sample().copy(traceIds = listOf("trace:a")).seal()
        val tampered = sealed.copy(traceIds = listOf("trace:a", "trace:injected"))
        val verification = V17AcceptanceGate.verify(tampered)

        assertTrue(V17EvidenceFailureCode.PROVENANCE_HASH_MISMATCH in verification.failures)
        assertTrue(V17EvidenceFailureCode.RECORD_HASH_MISMATCH in verification.failures)
    }

    @Test
    fun tamperedLineageFailsClosed() {
        val sealed = sample().copy(parentEvidenceSha256s = listOf("1".repeat(64))).seal()
        val tampered = sealed.copy(parentEvidenceSha256s = listOf("2".repeat(64)))
        val verification = V17AcceptanceGate.verify(tampered)

        assertTrue(V17EvidenceFailureCode.LINEAGE_HASH_MISMATCH in verification.failures)
        assertTrue(V17EvidenceFailureCode.RECORD_HASH_MISMATCH in verification.failures)
    }

    @Test
    fun persistedEnvelopeRejectsPayloadAndStableIdentityMismatch() {
        val persisted = V17PersistedAcceptanceEvidence.from(sample().seal())
        val badPayload = persisted.copy(payloadSha256 = "0".repeat(64))
        val badIdentity = persisted.copy(persistenceId = "wrong-record")

        assertTrue(
            V17EvidenceFailureCode.PERSISTED_PAYLOAD_HASH_MISMATCH in
                V17AcceptanceGate.verifyPersisted(badPayload).failures
        )
        assertTrue(
            V17EvidenceFailureCode.PERSISTENCE_ID_MISMATCH in
                V17AcceptanceGate.verifyPersisted(badIdentity).failures
        )
    }

    @Test
    fun exactCandidateHeadIsExternallyBound() {
        val sealed = sample().seal()
        val otherCandidate = "a".repeat(40)
        val verification = V17AcceptanceGate.verify(
            evidence = sealed,
            expectedCandidateSha = otherCandidate,
        )

        assertTrue(V17EvidenceFailureCode.CANDIDATE_SHA_MISMATCH in verification.failures)
        assertFailsWith<IllegalArgumentException> {
            V17AcceptanceGate.requirePass(sealed, otherCandidate)
        }
    }

    @Test
    fun changedRelevantIdentityProducesNewEvidenceIdentity() {
        val first = sample().seal()
        val changed = sample().copy(runId = "run-2").seal()

        assertNotEquals(first.integrity?.evidenceId, changed.integrity?.evidenceId)
        assertNotEquals(first.integrity?.recordSha256, changed.integrity?.recordSha256)
    }

    @Test
    fun incompleteJourneyReportsTypedFailureCodes() {
        val verification = V17AcceptanceGate.verifyCompleteJourneyEvidence(
            V17Journey.DEEPSEARCH_ARTIFACT_TO_COGNITION,
            sample().seal(),
        )

        assertTrue(V17EvidenceFailureCode.MISSING_TRACE_EVIDENCE in verification.failures)
        assertTrue(V17EvidenceFailureCode.MISSING_RESOURCE_EVIDENCE in verification.failures)
        assertTrue(V17EvidenceFailureCode.MISSING_OUTCOME_EVIDENCE in verification.failures)
    }

    private fun sample() = V17AcceptanceEvidence(
        candidateSha = "0123456789abcdef0123456789abcdef01234567",
        scenarioId = "crash-goal-after-resource-reservation",
        environment = "unit",
        runId = "run-1",
        observedDurableState = "RESERVED",
        duplicateEffectCount = 0,
    )
}
