package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.level7.EvidenceActionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaInformationActionBridgeTest {
    @Test
    fun unresolvedPairsReuseExistingPlannerWithoutExecutionAuthority() {
        val pair = CandidatePair("a", "b")
        val inference = MetaInferenceResult(
            sourceGroupFingerprint = "group",
            candidateIds = listOf("a", "b"),
            status = MetaInferenceStatus.INFORMATION_REQUIRED,
            identifiability = LayeredIdentifiabilityAssessment(
                pairSeparations = listOf(PairSeparation(pair, null)),
                completeSeparatingDepth = null,
                unresolvedPairs = listOf(pair),
                maximumDepth = 2,
                fingerprint = "ident",
            ),
            structuralSignatures = emptyList(),
            support = MetaSupportRankAnalyzer().assess(null),
            realizationGap = null,
            deformation = null,
            fingerprint = "inference",
        )
        val candidates = MetaInformationActionBridge().candidates(inference)

        assertEquals(
            setOf(EvidenceActionKind.MEMORY_LOOKUP, EvidenceActionKind.ASK_USER),
            candidates.map { it.kind }.toSet(),
        )
        val plan = InformationActionPlanner().plan(
            sourceCycleId = "cycle",
            budgetFingerprint = "budget",
            inference = inference,
            allowedKinds = candidates.mapTo(linkedSetOf()) { it.kind },
            candidates = candidates,
        )
        assertTrue(plan.items.isNotEmpty())
        assertTrue(plan.items.none { it.executionAuthority })
        assertFalse(plan.executionAuthority)
    }
}
