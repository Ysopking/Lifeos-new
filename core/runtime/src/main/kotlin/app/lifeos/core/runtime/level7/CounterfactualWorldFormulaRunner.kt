package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.world.WorldFormulaExecution
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaRequest
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace

data class CounterfactualWorldFormulaInput(
    val baseProductiveSnapshotId: String,
    val baseEquationVersion: String,
    val interventionFingerprint: String,
    val request: WorldFormulaRequest,
) {
    init {
        require(baseProductiveSnapshotId.isNotBlank())
        require(baseEquationVersion.isNotBlank())
        require(interventionFingerprint.isNotBlank())
        require(request.equationVersion == baseEquationVersion)
    }
}

data class CounterfactualWorldSnapshot(
    val id: String,
    val namespace: WorldFormulaSnapshotNamespace,
    val baseProductiveSnapshotId: String,
    val interventionFingerprint: String,
    val snapshot: WorldFormulaSnapshot,
) {
    init {
        require(namespace == WorldFormulaSnapshotNamespace.COUNTERFACTUAL)
        require(baseProductiveSnapshotId.isNotBlank())
        require(interventionFingerprint.isNotBlank())
        require(id.isNotBlank())
    }

    val productiveCommitAllowed: Boolean get() = false
}

class CounterfactualWorldFormulaRunner(
    private val evaluate: suspend (WorldFormulaRequest) -> WorldFormulaExecution,
) {
    suspend fun run(input: CounterfactualWorldFormulaInput): CounterfactualWorldSnapshot {
        val execution = evaluate(input.request)
        require(execution.state == WorldFormulaExecutionState.COMPLETED)
        val snapshot = requireNotNull(execution.snapshot)
        require(snapshot.equationVersion == input.baseEquationVersion)
        val id = "counterfactual-world:${StableFieldIds.fingerprint(
            "counterfactual-world/v1",
            input.baseProductiveSnapshotId,
            input.interventionFingerprint,
            snapshot.id,
            snapshot.contentFingerprint(),
        )}"
        return CounterfactualWorldSnapshot(
            id = id,
            namespace = WorldFormulaSnapshotNamespace.COUNTERFACTUAL,
            baseProductiveSnapshotId = input.baseProductiveSnapshotId,
            interventionFingerprint = input.interventionFingerprint,
            snapshot = snapshot,
        )
    }
}
