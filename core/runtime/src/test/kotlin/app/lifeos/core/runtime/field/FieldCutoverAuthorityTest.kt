package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

class FieldCutoverAuthorityTest {
    private val domain = FieldDomainId("domain:test")
    private val at = Instant.parse("2026-09-19T02:00:00Z")

    @Test
    fun selectedDomainBecomesEligibleOnlyAfterMinimumEquivalentReplayCorpus() = runBlocking {
        val ledger = BoundedFieldShadowValidationLedger()
        val repository = MemoryStateRepository()
        val validator = FieldShadowValidator(
            FieldShadowValidationPolicy(
                minimumReplayCases = 2,
                selectedDomains = setOf(domain),
            )
        )
        val authority = FieldCutoverAuthority(ledger, repository, validator)

        ledger.record(evidence("case-1", "evidence-1"))
        val first = authority.assess(domain, at, "replay-gate")
        assertEquals(FieldCutoverMode.SHADOW, first.state.mode)
        assertEquals(FieldCutoverRecommendation.INSUFFICIENT_REPLAY_CORPUS, first.report.recommendation)

        ledger.record(evidence("case-2", "evidence-2"))
        val second = authority.assess(domain, at.plusSeconds(1), "replay-gate")
        assertEquals(FieldCutoverMode.ELIGIBLE, second.state.mode)
        assertEquals(FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN, second.report.recommendation)
    }

    @Test
    fun genericShadowDomainCanNeverBecomeEligibleOrAuthoritative() = runBlocking {
        val shadowDomain = DefaultPhotonFieldRequestFactory.DOMAIN_ID
        val ledger = BoundedFieldShadowValidationLedger()
        val repository = MemoryStateRepository()
        val validator = FieldShadowValidator(
            FieldShadowValidationPolicy(
                minimumReplayCases = 1,
                selectedDomains = setOf(shadowDomain),
            )
        )
        val authority = FieldCutoverAuthority(ledger, repository, validator)
        ledger.record(evidence("shadow-case", "shadow-evidence", domainId = shadowDomain))

        val assessed = authority.assess(shadowDomain, at, "misconfigured-selection")

        assertEquals(
            FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN,
            assessed.report.recommendation,
        )
        assertEquals(FieldCutoverMode.SHADOW, assessed.state.mode)
        assertFailsWith<IllegalArgumentException> {
            authority.activate(
                shadowDomain,
                assessed.evidenceFingerprint,
                at.plusSeconds(1),
                "forbidden-activation",
            )
        }
    }

    @Test
    fun activationRequiresExactPersistedEvidenceFingerprint() = runBlocking {
        val ledger = BoundedFieldShadowValidationLedger()
        val repository = MemoryStateRepository()
        val validator = FieldShadowValidator(
            FieldShadowValidationPolicy(
                minimumReplayCases = 1,
                selectedDomains = setOf(domain),
            )
        )
        val authority = FieldCutoverAuthority(ledger, repository, validator)
        ledger.record(evidence("case-1", "evidence-1"))
        val eligible = authority.assess(domain, at, "gate")

        assertFailsWith<IllegalArgumentException> {
            authority.activate(domain, "0".repeat(64), at.plusSeconds(1), "activation")
        }

        val active = authority.activate(
            domain,
            eligible.evidenceFingerprint,
            at.plusSeconds(1),
            "activation",
        )
        assertEquals(FieldCutoverMode.AUTHORITATIVE, active.mode)
        assertEquals(2L, active.generation)
        assertEquals(eligible.evidenceFingerprint, active.evidenceFingerprint)
    }

    @Test
    fun newBlockingEvidenceRevokesAuthoritativeDomainBackToShadow() = runBlocking {
        val ledger = BoundedFieldShadowValidationLedger()
        val repository = MemoryStateRepository()
        val validator = FieldShadowValidator(
            FieldShadowValidationPolicy(
                minimumReplayCases = 1,
                selectedDomains = setOf(domain),
            )
        )
        val authority = FieldCutoverAuthority(ledger, repository, validator)
        ledger.record(evidence("case-1", "evidence-1"))
        val eligible = authority.assess(domain, at, "gate")
        val active = authority.activate(domain, eligible.evidenceFingerprint, at.plusSeconds(1), "activation")

        ledger.record(
            evidence(
                replayCaseId = "case-2",
                id = "evidence-blocker",
                universalState = ShadowSemanticState.RESOLVED,
                legacyState = ShadowSemanticState.UNRESOLVED,
            )
        )
        val revoked = authority.assess(domain, at.plusSeconds(2), "continuous-validation")

        assertEquals(FieldCutoverMode.SHADOW, revoked.state.mode)
        assertEquals(active.generation + 1L, revoked.state.generation)
        assertNotEquals(active.evidenceFingerprint, revoked.evidenceFingerprint)
    }

    @Test
    fun failedCasNeverAcknowledgesActivation() = runBlocking {
        val ledger = BoundedFieldShadowValidationLedger()
        val repository = MemoryStateRepository()
        val validator = FieldShadowValidator(
            FieldShadowValidationPolicy(
                minimumReplayCases = 1,
                selectedDomains = setOf(domain),
            )
        )
        val authority = FieldCutoverAuthority(ledger, repository, validator)
        ledger.record(evidence("case-1", "evidence-1"))
        val eligible = authority.assess(domain, at, "gate")
        repository.failNextCas = true

        assertFailsWith<IllegalStateException> {
            authority.activate(domain, eligible.evidenceFingerprint, at.plusSeconds(1), "activation")
        }
        assertEquals(FieldCutoverMode.ELIGIBLE, repository.load(domain)?.mode)
    }

    private fun evidence(
        replayCaseId: String,
        id: String,
        universalState: ShadowSemanticState = ShadowSemanticState.RESOLVED,
        legacyState: ShadowSemanticState = ShadowSemanticState.RESOLVED,
        domainId: FieldDomainId = domain,
    ) = FieldShadowValidationEvidence(
        id = id,
        taskId = TaskId("task-" + replayCaseId),
        domainId = domainId,
        origin = FieldShadowValidationOrigin.DETERMINISTIC_REPLAY,
        replayCaseId = replayCaseId,
        legacy = LegacyFieldObservation(
            finalState = TaskState.COMPLETED,
            semanticState = legacyState,
            influenceCount = 1,
            influenceTypes = setOf("FIELD"),
            averageConfidence = 0.9,
            totalEnergyDelta = 0.1,
        ),
        universal = UniversalFieldObservation(
            shadowState = FieldShadowState.COMPLETED,
            semanticState = universalState,
            convergenceStatus = app.lifeos.core.field.ConvergenceStatus.CONVERGED,
            snapshotPresent = true,
            winnerCount = 1,
            topConfidence = 0.9,
        ),
        sourceStatus = ShadowSourceStatus.PRESERVED,
        taskOwnershipStatus = ShadowTaskOwnershipStatus.PRESERVED,
        difference = when {
            legacyState == ShadowSemanticState.UNRESOLVED &&
                universalState == ShadowSemanticState.RESOLVED ->
                FieldShadowDifferenceClass.UNRESOLVED_COLLAPSED
            else -> FieldShadowDifferenceClass.EQUIVALENT
        },
        confidenceDelta = 0.0,
        capturedAt = at,
    )

    private class MemoryStateRepository : FieldCutoverStateRepository {
        private val values = mutableMapOf<FieldDomainId, FieldCutoverState>()
        var failNextCas: Boolean = false

        override suspend fun load(domainId: FieldDomainId): FieldCutoverState? = values[domainId]

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            state: FieldCutoverState,
        ): Boolean {
            if (failNextCas) {
                failNextCas = false
                return false
            }
            val current = values[state.domainId]
            if (current?.revision != expectedRevision) return false
            values[state.domainId] = state
            return true
        }
    }
}
