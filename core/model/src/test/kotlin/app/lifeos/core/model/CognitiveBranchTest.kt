package app.lifeos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class CognitiveBranchTest {
    private val inputHash = StableCognitiveIds.stateHash("input")
    private val outputHash = StableCognitiveIds.stateHash("output")
    private val photonId = PhotonId("photon-1")
    private val traceId = StableCognitiveIds.trace(photonId, 1, inputHash)
    private val module = ModuleIdentity("field-a", "1.0.0", "impl-a")
    private val branchId = StableCognitiveIds.branch(traceId, photonId, module.stableFingerprint, 0)

    @Test fun branchLifecyclePreservesIdentity() {
        val created = CognitiveBranch(
            branchId = branchId,
            traceId = traceId,
            parentPhotonId = photonId,
            parentRevision = 1,
            ordinal = 0,
            module = module,
            inputStateHash = inputHash,
        )
        val processing = created.processing()
        val ready = processing.ready(listOf(PhotonId("out-1")), outputHash)
        val converged = ready.converged()

        assertEquals(branchId, converged.branchId)
        assertEquals(CognitiveBranchStatus.CONVERGED, converged.status)
        assertEquals(outputHash, converged.outputStateHash)
    }

    @Test fun completedBranchRequiresOutputHash() {
        assertFails {
            CognitiveBranch(
                branchId = branchId,
                traceId = traceId,
                parentPhotonId = photonId,
                parentRevision = 1,
                ordinal = 0,
                module = module,
                inputStateHash = inputHash,
                status = CognitiveBranchStatus.READY_FOR_CONVERGENCE,
            )
        }
    }

    @Test fun onlyReadyBranchCanBecomeConverged() {
        val created = CognitiveBranch(
            branchId = branchId,
            traceId = traceId,
            parentPhotonId = photonId,
            parentRevision = 1,
            ordinal = 0,
            module = module,
            inputStateHash = inputHash,
        )
        assertFails { created.converged() }
    }

    @Test fun convergenceContractRequiresSelectedBranchOnlyWhenConverged() {
        val selected = CognitiveConvergenceRecord(
            convergenceId = StableCognitiveIds.convergence(traceId, listOf(branchId)),
            traceId = traceId,
            branchIds = listOf(branchId),
            selectedBranchId = branchId,
            status = CognitiveConvergenceStatus.CONVERGED,
            inputStateHash = inputHash,
            outputStateHash = outputHash,
            reasonFingerprint = StableCognitiveIds.fingerprint("winner", branchId.value),
        )
        assertEquals(branchId, selected.selectedBranchId)

        assertFails {
            selected.copy(
                selectedBranchId = null,
                status = CognitiveConvergenceStatus.CONVERGED,
            )
        }
        assertFails {
            selected.copy(
                selectedBranchId = branchId,
                status = CognitiveConvergenceStatus.UNRESOLVED,
            )
        }
    }

    @Test fun canonicalBranchesAreStableAcrossInputOrder() {
        val other = StableCognitiveIds.branch(traceId, photonId, module.stableFingerprint, 1)
        val convergenceId = StableCognitiveIds.convergence(traceId, listOf(branchId, other))
        val left = CognitiveConvergenceRecord(
            convergenceId = convergenceId,
            traceId = traceId,
            branchIds = listOf(other, branchId),
            selectedBranchId = null,
            status = CognitiveConvergenceStatus.UNRESOLVED,
            inputStateHash = inputHash,
            outputStateHash = outputHash,
            reasonFingerprint = StableCognitiveIds.fingerprint("unresolved"),
        )
        val right = left.copy(branchIds = listOf(branchId, other))
        assertEquals(left.canonicalBranchIds, right.canonicalBranchIds)
    }
}
