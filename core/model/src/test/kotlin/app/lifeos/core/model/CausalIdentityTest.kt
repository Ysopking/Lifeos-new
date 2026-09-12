package app.lifeos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class CausalIdentityTest {
    private val inputHash = StableCognitiveIds.stateHash("input", "A")

    @Test fun sameInputsProduceSameTraceBranchProcessingAndConvergenceIds() {
        val photonId = PhotonId("photon-1")
        val traceA = StableCognitiveIds.trace(photonId, 2, inputHash)
        val traceB = StableCognitiveIds.trace(photonId, 2, inputHash)
        assertEquals(traceA, traceB)

        val branchA = StableCognitiveIds.branch(traceA, photonId, "legal-advice", 0)
        val branchB = StableCognitiveIds.branch(traceB, photonId, "legal-advice", 0)
        assertEquals(branchA, branchB)

        val module = ModuleIdentity("legal-advice", "1.0.0", "impl-sha")
        val processingA = StableCognitiveIds.moduleProcessing(traceA, branchA, module.stableFingerprint, photonId, 2)
        val processingB = StableCognitiveIds.moduleProcessing(traceB, branchB, module.stableFingerprint, photonId, 2)
        assertEquals(processingA, processingB)

        val convergenceA = StableCognitiveIds.convergence(traceA, listOf(branchA))
        val convergenceB = StableCognitiveIds.convergence(traceB, listOf(branchB))
        assertEquals(convergenceA, convergenceB)
    }

    @Test fun branchOrdinalAndModuleImplementationChangeIdentity() {
        val photonId = PhotonId("photon-1")
        val trace = StableCognitiveIds.trace(photonId, 1, inputHash)
        val first = StableCognitiveIds.branch(trace, photonId, "module", 0)
        val second = StableCognitiveIds.branch(trace, photonId, "module", 1)
        assertNotEquals(first, second)

        val v1 = ModuleIdentity("module", "1", "sha-a")
        val v2 = ModuleIdentity("module", "1", "sha-b")
        assertNotEquals(v1.stableFingerprint, v2.stableFingerprint)
    }

    @Test fun convergenceIsIndependentOfBranchCollectionOrder() {
        val photonId = PhotonId("photon-1")
        val trace = StableCognitiveIds.trace(photonId, 1, inputHash)
        val a = StableCognitiveIds.branch(trace, photonId, "a", 0)
        val b = StableCognitiveIds.branch(trace, photonId, "b", 0)
        assertEquals(
            StableCognitiveIds.convergence(trace, listOf(a, b)),
            StableCognitiveIds.convergence(trace, listOf(b, a)),
        )
    }

    @Test fun hashingPreservesExactContentAndStructuralBoundaries() {
        assertNotEquals(StableCognitiveIds.stateHash("A"), StableCognitiveIds.stateHash("a"))
        assertNotEquals(
            StableCognitiveIds.stateHash("ab", "c"),
            StableCognitiveIds.stateHash("a", "bc"),
        )
    }

    @Test fun invalidReplayContractsAreRejected() {
        assertFails { CognitiveStateHash("not-a-digest") }
        assertFails { LogicalTick(-1) }
        assertFails { StableCognitiveIds.trace(PhotonId("p"), 0, inputHash) }
        val trace = StableCognitiveIds.trace(PhotonId("p"), 1, inputHash)
        assertFails { StableCognitiveIds.branch(trace, PhotonId("p"), "module", -1) }
        assertFails { StableCognitiveIds.convergence(trace, emptyList()) }
    }

    @Test fun moduleCapabilityOrderDoesNotChangeFingerprint() {
        val left = ModuleIdentity("module", "1", "sha", setOf("b", "a"))
        val right = ModuleIdentity("module", "1", "sha", setOf("a", "b"))
        assertEquals(left.stableFingerprint, right.stableFingerprint)
    }
}
