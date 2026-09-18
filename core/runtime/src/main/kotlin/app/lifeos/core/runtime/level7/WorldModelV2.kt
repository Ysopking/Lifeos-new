package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace

data class WorldModelScenario(
    val id: String,
    val baseProductiveSnapshotId: String,
    val baseEquationVersion: String,
    val namespace: WorldFormulaSnapshotNamespace,
    val interventionFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(baseProductiveSnapshotId.isNotBlank())
        require(baseEquationVersion.isNotBlank())
        require(interventionFingerprint.isNotBlank())
        require(namespace != WorldFormulaSnapshotNamespace.PRODUCTIVE) {
            "Simulation/counterfactual state cannot be productive world state"
        }
    }

    val productiveCommitAllowed: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-world-model-scenario/v2",
        id,
        baseProductiveSnapshotId,
        baseEquationVersion,
        namespace.name,
        interventionFingerprint,
    )
}

data class CounterfactualPrediction(
    val scenarioId: String,
    val predictedStateFingerprint: String,
    val outcomeFingerprint: String,
    val confidence: Double,
) {
    init {
        require(scenarioId.isNotBlank())
        require(predictedStateFingerprint.isNotBlank())
        require(outcomeFingerprint.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    val productiveWorldAuthority: Boolean get() = false
}

object WorldModelNamespaceGate {
    fun counterfactual(
        baseProductiveSnapshotId: String,
        baseEquationVersion: String,
        interventionFingerprint: String,
    ): WorldModelScenario {
        val id = "world-model-scenario:${StableFieldIds.fingerprint(
            "level7-world-model-scenario-id/v2",
            baseProductiveSnapshotId,
            baseEquationVersion,
            interventionFingerprint,
        )}"
        return WorldModelScenario(
            id = id,
            baseProductiveSnapshotId = baseProductiveSnapshotId,
            baseEquationVersion = baseEquationVersion,
            namespace = WorldFormulaSnapshotNamespace.COUNTERFACTUAL,
            interventionFingerprint = interventionFingerprint,
        )
    }
}
