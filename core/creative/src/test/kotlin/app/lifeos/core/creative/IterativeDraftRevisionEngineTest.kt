package app.lifeos.core.creative

import app.lifeos.core.model.StableCognitiveIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IterativeDraftRevisionEngineTest {
    @Test
    fun already_clean_draft_converges_without_revision() {
        val initial = evidence(
            seed = "clean",
            status = DocumentCritiqueStatus.PASSED,
            errors = 0,
            warnings = 0,
        )

        val report = IterativeDraftRevisionEngine().converge(initial, emptyList())

        assertEquals(DraftRevisionLoopState.CONVERGED, report.state)
        assertEquals(0, report.acceptedRevisionCount)
        assertEquals(initial, report.finalEvidence)
        assertFalse(report.finalizationAuthority)
        assertFalse(report.factualAuthority)
        assertFalse(report.ownerStyleAuthority)
    }

    @Test
    fun blocking_factual_evidence_stops_before_revision() {
        val initial = evidence(
            seed = "blocked",
            status = DocumentCritiqueStatus.BLOCKED,
            errors = 1,
            warnings = 0,
            factualPassed = false,
        )

        val report = IterativeDraftRevisionEngine().converge(initial, emptyList())

        assertEquals(DraftRevisionLoopState.BLOCKED, report.state)
        assertEquals("initial-draft-blocked", report.reasonCode)
    }

    @Test
    fun strictly_better_revalidated_candidate_converges() {
        val initial = evidence(
            seed = "initial",
            status = DocumentCritiqueStatus.REVISION_RECOMMENDED,
            errors = 0,
            warnings = 1,
        )
        val candidate = evidence(
            seed = "candidate",
            status = DocumentCritiqueStatus.PASSED,
            errors = 0,
            warnings = 0,
            goalFingerprint = initial.documentGoalFingerprint,
        )
        val attempt = DraftRevisionAttempt.create(
            ordinal = 1,
            parentEvidence = initial,
            evidence = candidate,
            changedSurfaceFingerprints = listOf(fp("surface-1")),
            reasonCodes = listOf("reduce-redundancy"),
        )

        val report = IterativeDraftRevisionEngine().converge(initial, listOf(attempt))

        assertEquals(DraftRevisionLoopState.CONVERGED, report.state)
        assertEquals(1, report.acceptedRevisionCount)
        assertEquals(candidate, report.finalEvidence)
    }

    @Test
    fun candidate_without_strict_critique_improvement_stalls() {
        val initial = evidence(
            seed = "initial-stall",
            status = DocumentCritiqueStatus.REVISION_RECOMMENDED,
            errors = 0,
            warnings = 1,
        )
        val candidate = evidence(
            seed = "candidate-stall",
            status = DocumentCritiqueStatus.REVISION_RECOMMENDED,
            errors = 0,
            warnings = 1,
            goalFingerprint = initial.documentGoalFingerprint,
        )
        val attempt = DraftRevisionAttempt.create(
            ordinal = 1,
            parentEvidence = initial,
            evidence = candidate,
            changedSurfaceFingerprints = listOf(fp("surface-stall")),
            reasonCodes = listOf("style"),
        )

        val report = IterativeDraftRevisionEngine().converge(initial, listOf(attempt))

        assertEquals(DraftRevisionLoopState.STALLED, report.state)
        assertEquals(0, report.acceptedRevisionCount)
        assertEquals(initial, report.finalEvidence)
        assertEquals("candidate-did-not-improve-critique", report.reasonCode)
    }

    @Test
    fun candidate_that_fails_factual_revalidation_is_blocked() {
        val initial = evidence(
            seed = "initial-factual",
            status = DocumentCritiqueStatus.REVISION_RECOMMENDED,
            errors = 0,
            warnings = 2,
        )
        val candidate = evidence(
            seed = "candidate-factual",
            status = DocumentCritiqueStatus.BLOCKED,
            errors = 1,
            warnings = 0,
            factualPassed = false,
            goalFingerprint = initial.documentGoalFingerprint,
        )
        val attempt = DraftRevisionAttempt.create(
            ordinal = 1,
            parentEvidence = initial,
            evidence = candidate,
            changedSurfaceFingerprints = listOf(fp("surface-factual")),
            reasonCodes = listOf("shorten-paragraph"),
        )

        val report = IterativeDraftRevisionEngine().converge(initial, listOf(attempt))

        assertEquals(DraftRevisionLoopState.BLOCKED, report.state)
        assertEquals(0, report.acceptedRevisionCount)
        assertEquals(initial, report.finalEvidence)
    }

    @Test
    fun bounded_loop_reports_max_rounds_when_improvement_is_not_yet_converged() {
        val initial = evidence(
            seed = "initial-bounded",
            status = DocumentCritiqueStatus.REVISION_RECOMMENDED,
            errors = 0,
            warnings = 2,
        )
        val candidate = evidence(
            seed = "candidate-bounded",
            status = DocumentCritiqueStatus.REVISION_RECOMMENDED,
            errors = 0,
            warnings = 1,
            goalFingerprint = initial.documentGoalFingerprint,
        )
        val attempt = DraftRevisionAttempt.create(
            ordinal = 1,
            parentEvidence = initial,
            evidence = candidate,
            changedSurfaceFingerprints = listOf(fp("surface-bounded")),
            reasonCodes = listOf("reduce-findings"),
        )

        val report = IterativeDraftRevisionEngine(maxRounds = 1)
            .converge(initial, listOf(attempt))

        assertEquals(DraftRevisionLoopState.MAX_ROUNDS_REACHED, report.state)
        assertEquals(1, report.acceptedRevisionCount)
        assertEquals(candidate, report.finalEvidence)
    }

    private fun evidence(
        seed: String,
        status: DocumentCritiqueStatus,
        errors: Int,
        warnings: Int,
        factualPassed: Boolean = true,
        goalFingerprint: String = fp("goal"),
    ): DraftRevisionEvidence {
        val findings = (0 until errors + warnings)
            .map { index -> fp("$seed-finding-$index") }
        return DraftRevisionEvidence.fromSignals(
            documentGoalFingerprint = goalFingerprint,
            draftFingerprint = fp("$seed-draft"),
            factualValidationFingerprint = fp("$seed-factual"),
            factualPassed = factualPassed,
            critiqueFingerprint = fp("$seed-critique"),
            critiqueStatus = status,
            errorCount = errors,
            warningCount = warnings,
            findingFingerprints = findings,
        )
    }

    private fun fp(value: String): String =
        StableCognitiveIds.fingerprint("b440-test/v1", value)
}
