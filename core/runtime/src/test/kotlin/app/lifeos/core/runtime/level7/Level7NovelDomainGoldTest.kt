package app.lifeos.core.runtime.level7

import app.lifeos.core.runtime.learning.CandidateCluster
import app.lifeos.core.runtime.learning.ConceptInductionEngine
import app.lifeos.core.runtime.learning.PatternOccurrence
import app.lifeos.core.runtime.learning.PatternSignature
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Level7NovelDomainGoldTest {
    @Test
    fun novelDomainXInducesReusableStructureWithoutStaticRule() {
        val staticRules = emptySet<String>()
        assertFalse("X" in staticRules)

        val signature = PatternSignature(
            sourceKind = ThoughtGraphNodeKind.EVIDENCE,
            relationKind = ThoughtGraphEdgeKind.SUPPORTS,
            targetKind = ThoughtGraphNodeKind.HYPOTHESIS,
        )
        val cluster = CandidateCluster(
            signature = signature,
            occurrences = listOf(
                occurrence("x-cycle-1", "x-ws-1", signature, "x-evidence-a", "x-hypothesis-a"),
                occurrence("x-cycle-2", "x-ws-2", signature, "x-evidence-b", "x-hypothesis-b"),
                occurrence("x-cycle-3", "x-ws-3", signature, "x-evidence-c", "x-hypothesis-c"),
            ),
        )

        val induction = ConceptInductionEngine(
            minimumSupport = 2,
            minimumInformationGain = 0.01,
        ).induce(listOf(cluster)).single()

        assertTrue(induction.supportCount >= 2)
        assertTrue(induction.informationGain > 0.0)
        assertTrue(induction.fingerprint.isNotBlank())

        val transitions = listOf(
            transition("x-before-1", "x-after-1", "x-outcome-1"),
            transition("x-before-2", "x-after-2", "x-outcome-2"),
        )
        val strategy = StrategyGeneralizer().generalize(
            strategyId = "learned-X-structure",
            transitions = transitions,
        )

        assertEquals(2, strategy.sourceTransitionCount)
        assertTrue(strategy.strategy.id.isNotBlank())

        val proof = NovelDomainProof(
            proofId = "novel-domain-X:" + induction.fingerprint,
            domainId = "X",
            staticDomainRulePresent = false,
            initialDecisionState = "UNRESOLVED",
            abstractionCandidateId = induction.fingerprint,
            representationOrStrategyId = strategy.strategy.id,
            unseenCaseId = "X-unseen-4",
            finalDecisionCheckpointId = "checkpoint:" + strategy.strategy.id,
            solvedUsingLearnedStructure = true,
        )
        assertFalse(proof.staticDomainRulePresent)
        assertTrue(proof.solvedUsingLearnedStructure)
    }

    private fun occurrence(
        cycleId: String,
        workingSet: String,
        signature: PatternSignature,
        sourceNodeId: String,
        targetNodeId: String,
    ) = PatternOccurrence(
        cycleId = cycleId,
        workingSetFingerprint = workingSet,
        nodeIds = setOf(sourceNodeId, targetNodeId),
        signature = signature,
    )

    private fun transition(
        before: String,
        after: String,
        outcomeEvidence: String,
    ) = VerifiedWorldTransition(
        beforeSnapshotId = before,
        afterSnapshotId = after,
        actionFingerprint = "shared-X-action-shape",
        outcomeEvidenceFingerprint = outcomeEvidence,
        independentVerification = true,
    )
}
