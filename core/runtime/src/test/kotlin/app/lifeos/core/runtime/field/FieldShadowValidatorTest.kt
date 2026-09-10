package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FieldShadowValidatorTest {
    private val domain = StableFieldIds.domain("test.shadow.cutover")
    private val fixedTime = Instant.parse("2026-09-10T10:00:00Z")

    @Test
    fun `selected domain becomes recommendation-ready only after complete deterministic replay corpus`() {
        val validator = validator(minimumReplayCases = 2)
        val evidence = listOf(
            validator.validate(input(caseId = "case-a")),
            validator.validate(input(caseId = "case-b")),
        )

        val report = validator.report(domain, evidence)

        assertEquals(FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN, report.recommendation)
        assertTrue(report.passed)
        assertEquals(2, report.replayCaseCount)
        assertEquals(2, report.equivalentCaseCount)
        assertTrue(report.blockerClasses.isEmpty())
    }

    @Test
    fun `live observations never satisfy deterministic replay corpus`() {
        val validator = validator(minimumReplayCases = 1)
        val live = validator.validate(
            input(caseId = null, origin = FieldShadowValidationOrigin.LIVE),
        )

        val report = validator.report(domain, listOf(live))

        assertEquals(FieldCutoverRecommendation.INSUFFICIENT_REPLAY_CORPUS, report.recommendation)
        assertEquals(0, report.replayCaseCount)
    }

    @Test
    fun `unselected domain is denied even with perfect replay evidence`() {
        val validator = FieldShadowValidator(
            policy = FieldShadowValidationPolicy(
                minimumReplayCases = 1,
                selectedDomains = emptySet(),
            ),
            now = { fixedTime },
        )
        val evidence = validator.validate(input(caseId = "case-a"))

        val report = validator.report(domain, listOf(evidence))

        assertEquals(FieldCutoverRecommendation.NOT_SELECTED, report.recommendation)
        assertFalse(report.passed)
    }

    @Test
    fun `legacy unresolved state must remain unresolved`() {
        val validator = validator(minimumReplayCases = 1)
        val evidence = validator.validate(
            input(
                caseId = "conflict",
                legacyState = ShadowSemanticState.UNRESOLVED,
                universalState = ShadowSemanticState.UNRESOLVED,
                universalStatus = ConvergenceStatus.UNRESOLVED,
            ),
        )

        val report = validator.report(domain, listOf(evidence))

        assertEquals(FieldShadowDifferenceClass.EQUIVALENT_UNRESOLVED, evidence.difference)
        assertEquals(FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN, report.recommendation)
        assertEquals(0, report.unresolvedCollapsedRegressions)
    }

    @Test
    fun `resolved universal result cannot collapse legacy uncertainty`() {
        val validator = validator(minimumReplayCases = 1)
        val evidence = validator.validate(
            input(
                caseId = "conflict",
                legacyState = ShadowSemanticState.UNRESOLVED,
                universalState = ShadowSemanticState.RESOLVED,
            ),
        )

        val report = validator.report(domain, listOf(evidence))

        assertEquals(FieldShadowDifferenceClass.UNRESOLVED_COLLAPSED, evidence.difference)
        assertEquals(FieldCutoverRecommendation.BLOCKED, report.recommendation)
        assertEquals(1, report.unresolvedCollapsedRegressions)
    }

    @Test
    fun `source mutation blocks cutover before semantic equivalence can pass`() {
        val validator = validator(minimumReplayCases = 1)
        val evidence = validator.validate(
            input(
                caseId = "source-mutated",
                sourceStatus = ShadowSourceStatus.MISMATCH,
            ),
        )

        val report = validator.report(domain, listOf(evidence))

        assertEquals(FieldShadowDifferenceClass.SOURCE_MUTATION, evidence.difference)
        assertEquals(1, report.sourceMutationRegressions)
        assertEquals(FieldCutoverRecommendation.BLOCKED, report.recommendation)
    }

    @Test
    fun `task ownership regression blocks cutover before all other comparisons`() {
        val validator = validator(minimumReplayCases = 1)
        val evidence = validator.validate(
            input(
                caseId = "ownership",
                sourceStatus = ShadowSourceStatus.MISMATCH,
                ownershipStatus = ShadowTaskOwnershipStatus.REGRESSION,
            ),
        )

        val report = validator.report(domain, listOf(evidence))

        assertEquals(FieldShadowDifferenceClass.TASK_OWNERSHIP_REGRESSION, evidence.difference)
        assertEquals(1, report.taskOwnershipRegressions)
        assertEquals(FieldCutoverRecommendation.BLOCKED, report.recommendation)
    }

    @Test
    fun `same replay case with different outcomes is deterministic replay failure`() {
        val validator = validator(minimumReplayCases = 1)
        val first = validator.validate(input(caseId = "same-case"))
        val second = validator.validate(
            input(
                caseId = "same-case",
                universalState = ShadowSemanticState.UNRESOLVED,
                universalStatus = ConvergenceStatus.UNRESOLVED,
            ),
        )

        val report = validator.report(domain, listOf(first, second))

        assertEquals(FieldCutoverRecommendation.BLOCKED, report.recommendation)
        assertEquals(setOf("same-case"), report.nondeterministicReplayCases)
    }

    @Test
    fun `material confidence drift is explicit and blocks strict gate`() {
        val validator = validator(minimumReplayCases = 1, maximumConfidenceDelta = 0.10)
        val evidence = validator.validate(
            input(
                caseId = "confidence",
                legacyConfidence = 0.95,
                universalConfidence = 0.50,
            ),
        )

        val report = validator.report(domain, listOf(evidence))

        assertEquals(FieldShadowDifferenceClass.CONFIDENCE_DRIFT, evidence.difference)
        assertEquals(FieldCutoverRecommendation.BLOCKED, report.recommendation)
        assertEquals(1, report.semanticDivergences)
    }

    @Test
    fun `bounded ledger is idempotent and evicts oldest evidence`() = runTest {
        val validator = validator(minimumReplayCases = 1)
        val ledger = BoundedFieldShadowValidationLedger(capacity = 2)
        val first = validator.validate(input(caseId = "one"))
        val second = validator.validate(input(caseId = "two"))
        val third = validator.validate(input(caseId = "three"))

        assertTrue(ledger.record(first))
        assertFalse(ledger.record(first))
        assertTrue(ledger.record(second))
        assertTrue(ledger.record(third))

        val latest = ledger.latest(10)
        assertEquals(listOf(third.id, second.id), latest.map { it.id })
        assertTrue(ledger.forDomain(domain).none { it.id == first.id })
    }

    private fun validator(
        minimumReplayCases: Int,
        maximumConfidenceDelta: Double = 0.25,
    ) = FieldShadowValidator(
        policy = FieldShadowValidationPolicy(
            minimumReplayCases = minimumReplayCases,
            maximumConfidenceDelta = maximumConfidenceDelta,
            selectedDomains = setOf(domain),
        ),
        now = { fixedTime },
    )

    private fun input(
        caseId: String?,
        origin: FieldShadowValidationOrigin = FieldShadowValidationOrigin.DETERMINISTIC_REPLAY,
        legacyState: ShadowSemanticState = ShadowSemanticState.RESOLVED,
        universalState: ShadowSemanticState = ShadowSemanticState.RESOLVED,
        universalStatus: ConvergenceStatus = ConvergenceStatus.CONVERGED,
        legacyConfidence: Double = 0.90,
        universalConfidence: Double = 0.88,
        sourceStatus: ShadowSourceStatus = ShadowSourceStatus.PRESERVED,
        ownershipStatus: ShadowTaskOwnershipStatus = ShadowTaskOwnershipStatus.PRESERVED,
    ) = FieldShadowValidationInput(
        taskId = TaskId("task-${caseId ?: "live"}"),
        domainId = domain,
        legacy = LegacyFieldObservation(
            finalState = TaskState.COMPLETED,
            semanticState = legacyState,
            influenceCount = 1,
            influenceTypes = setOf(if (legacyState == ShadowSemanticState.UNRESOLVED) "INDEX_CONFLICT" else "INDEX"),
            averageConfidence = legacyConfidence,
            totalEnergyDelta = if (legacyState == ShadowSemanticState.UNRESOLVED) 0.0 else 0.8,
        ),
        universal = UniversalFieldObservation(
            shadowState = FieldShadowState.COMPLETED,
            semanticState = universalState,
            convergenceStatus = universalStatus,
            snapshotPresent = true,
            winnerCount = if (universalState == ShadowSemanticState.RESOLVED) 1 else 0,
            topConfidence = universalConfidence,
        ),
        sourceStatus = sourceStatus,
        taskOwnershipStatus = ownershipStatus,
        origin = origin,
        replayCaseId = caseId,
    )
}
