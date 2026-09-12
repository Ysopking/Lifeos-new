package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.field.world.WorldTransferCoefficient
import app.lifeos.core.runtime.resource.HardwareStateSnapshot

/**
 * Dedicated World Formula profile for the local device. It keeps hardware observations typed and
 * informational while producing a resource-controller readiness signal that V16 can consume.
 */
class HardwareWorldEquationProfile {
    val spec: WorldEquationSpec = WorldEquationSpec(
        version = VERSION,
        coefficients = listOf(
            READINESS_FROM_STABILITY,
            READINESS_FROM_CAPABILITY,
        ).sortedBy { it.id.value },
    )

    fun request(
        hardware: HardwareStateSnapshot,
        config: WorldFormulaConfig = WorldFormulaConfig(),
    ): WorldFormulaRequest {
        val hardwareInput = hardware.toWorldFormulaInput()
        val controllerTarget = WorldTargetRef(
            kind = WorldNodeKind.CAPABILITY,
            key = RESOURCE_CONTROLLER_KEY,
        )
        val controllerInput = WorldFormulaInputSnapshot(
            target = controllerTarget,
            vector = WorldFieldVector.EMPTY,
            sourceSnapshotFingerprint = StableFieldIds.fingerprint(
                "hardware-resource-controller/v1",
                hardware.fingerprint(),
            ),
        )
        return WorldFormulaRequest(
            inputs = listOf(hardwareInput, controllerInput),
            interactions = listOf(
                WorldFormulaInteraction(
                    source = hardwareInput.target,
                    target = controllerTarget,
                    sourceDimension = WorldSignalDimension.HEALTH_STABILITY,
                    targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
                    coefficientId = READINESS_FROM_STABILITY.id,
                    strength = 1.0,
                    explanation = "hardware stability constrains resource-controller readiness",
                ),
                WorldFormulaInteraction(
                    source = hardwareInput.target,
                    target = controllerTarget,
                    sourceDimension = WorldSignalDimension.CAPABILITY_READINESS,
                    targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
                    coefficientId = READINESS_FROM_CAPABILITY.id,
                    strength = 1.0,
                    explanation = "hardware compute/memory readiness constrains resource-controller readiness",
                ),
            ),
            equationVersion = spec.version,
            observedAt = hardware.observedAt,
            config = config,
            sourceTaskId = null,
            photonId = null,
        )
    }

    companion object {
        const val VERSION = "lifeos-world-hardware-resource-v1"
        const val RESOURCE_CONTROLLER_KEY = "resource-intelligence:local-device"

        private val READINESS_FROM_STABILITY = WorldTransferCoefficient.create(
            semanticKey = "hardware-stability-to-resource-readiness",
            sourceDimension = WorldSignalDimension.HEALTH_STABILITY,
            targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
            multiplier = 0.55,
            maxAbsoluteContribution = 0.55,
            explanation = "Thermal, energy, memory and storage stability contribute to usable resource readiness",
        )
        private val READINESS_FROM_CAPABILITY = WorldTransferCoefficient.create(
            semanticKey = "hardware-capability-to-resource-readiness",
            sourceDimension = WorldSignalDimension.CAPABILITY_READINESS,
            targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
            multiplier = 0.45,
            maxAbsoluteContribution = 0.45,
            explanation = "Compute and memory capability contribute to usable resource readiness",
        )
    }
}
