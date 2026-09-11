package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.field.world.WorldTransferCoefficient
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.ResourceBudgetDemand

/**
 * World Formula profile used by V16 to distribute the currently usable device envelope across
 * competing LIFEOS work domains. It does not grant resources and cannot exceed hard quotas.
 */
class ResourceAllocationWorldEquationProfile {
    val spec: WorldEquationSpec = WorldEquationSpec(
        version = VERSION,
        coefficients = listOf(
            SALIENCE_FROM_GOAL,
            SALIENCE_FROM_PRIORITY,
            SALIENCE_FROM_UTILITY,
            READINESS_FROM_HARDWARE,
            READINESS_FROM_STABILITY,
        ).sortedBy { it.id.value },
    )

    data class PreparedRequest(
        val request: WorldFormulaRequest,
        val laneInputs: Map<String, WorldFormulaInputSnapshot>,
    )

    fun request(
        hardware: HardwareStateSnapshot,
        demands: List<ResourceBudgetDemand>,
        config: WorldFormulaConfig = WorldFormulaConfig(),
    ): PreparedRequest {
        require(demands.isNotEmpty()) { "Resource allocation requires at least one demand" }
        require(demands.map { it.domain }.distinct().size == demands.size) {
            "Resource allocation may contain only one demand per domain"
        }

        val hardwareInput = hardware.toWorldFormulaInput()
        val workloadInputs = demands.sortedBy { it.domain.name }.associate { demand ->
            val provenance = demand.fingerprint()
            val input = WorldFormulaInputSnapshot(
                target = WorldTargetRef(
                    kind = WorldNodeKind.GOAL,
                    key = "resource-demand:${demand.domain.name.lowercase()}",
                ),
                vector = WorldFieldVector(
                    listOf(
                        WorldDimensionValue(
                            WorldSignalDimension.GOAL_RELEVANCE,
                            demand.goalRelevance,
                            demand.confidence,
                            setOf(provenance),
                        ),
                        WorldDimensionValue(
                            WorldSignalDimension.COGNITIVE_PRIORITY,
                            demand.priority,
                            demand.confidence,
                            setOf(provenance),
                        ),
                        WorldDimensionValue(
                            WorldSignalDimension.ANALYTIC_SALIENCE,
                            demand.expectedUtility,
                            demand.confidence,
                            setOf(provenance),
                        ),
                    )
                ),
                sourceSnapshotFingerprint = provenance,
            )
            demand.domain.name to input
        }
        val lanes = demands.sortedBy { it.domain.name }.associate { demand ->
            val key = demand.domain.name
            key to WorldFormulaInputSnapshot(
                target = WorldTargetRef(
                    kind = WorldNodeKind.CAPABILITY,
                    key = "resource-lane:${key.lowercase()}",
                ),
                vector = WorldFieldVector.EMPTY,
                sourceSnapshotFingerprint = StableFieldIds.fingerprint(
                    "resource-allocation-lane/v1",
                    hardware.fingerprint(),
                    demand.fingerprint(),
                ),
            )
        }

        val interactions = buildList {
            demands.sortedBy { it.domain.name }.forEach { demand ->
                val source = workloadInputs.getValue(demand.domain.name).target
                val lane = lanes.getValue(demand.domain.name).target
                add(
                    WorldFormulaInteraction(
                        source = source,
                        target = lane,
                        sourceDimension = WorldSignalDimension.GOAL_RELEVANCE,
                        targetDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
                        coefficientId = SALIENCE_FROM_GOAL.id,
                        strength = 1.0,
                        explanation = "goal relevance attracts shared resource capacity",
                    )
                )
                add(
                    WorldFormulaInteraction(
                        source = source,
                        target = lane,
                        sourceDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
                        targetDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
                        coefficientId = SALIENCE_FROM_PRIORITY.id,
                        strength = 1.0,
                        explanation = "work priority attracts shared resource capacity",
                    )
                )
                add(
                    WorldFormulaInteraction(
                        source = source,
                        target = lane,
                        sourceDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
                        targetDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
                        coefficientId = SALIENCE_FROM_UTILITY.id,
                        strength = 1.0,
                        explanation = "expected utility attracts shared resource capacity",
                    )
                )
                add(
                    WorldFormulaInteraction(
                        source = hardwareInput.target,
                        target = lane,
                        sourceDimension = WorldSignalDimension.CAPABILITY_READINESS,
                        targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
                        coefficientId = READINESS_FROM_HARDWARE.id,
                        strength = 1.0,
                        explanation = "device capability constrains this resource lane",
                    )
                )
                add(
                    WorldFormulaInteraction(
                        source = hardwareInput.target,
                        target = lane,
                        sourceDimension = WorldSignalDimension.HEALTH_STABILITY,
                        targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
                        coefficientId = READINESS_FROM_STABILITY.id,
                        strength = 1.0,
                        explanation = "device stability constrains this resource lane",
                    )
                )
            }
        }

        return PreparedRequest(
            request = WorldFormulaRequest(
                inputs = listOf(hardwareInput) + workloadInputs.values + lanes.values,
                interactions = interactions,
                equationVersion = spec.version,
                observedAt = hardware.observedAt,
                config = config,
            ),
            laneInputs = lanes,
        )
    }

    companion object {
        const val VERSION = "lifeos-world-resource-allocation-v1"

        private val SALIENCE_FROM_GOAL = WorldTransferCoefficient.create(
            semanticKey = "resource-goal-relevance-to-salience",
            sourceDimension = WorldSignalDimension.GOAL_RELEVANCE,
            targetDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
            multiplier = 0.45,
            maxAbsoluteContribution = 0.45,
            explanation = "Goal relevance contributes to resource allocation salience",
        )
        private val SALIENCE_FROM_PRIORITY = WorldTransferCoefficient.create(
            semanticKey = "resource-priority-to-salience",
            sourceDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            targetDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
            multiplier = 0.35,
            maxAbsoluteContribution = 0.35,
            explanation = "Current work priority contributes to resource allocation salience",
        )
        private val SALIENCE_FROM_UTILITY = WorldTransferCoefficient.create(
            semanticKey = "resource-utility-to-salience",
            sourceDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
            targetDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
            multiplier = 0.20,
            maxAbsoluteContribution = 0.20,
            explanation = "Expected utility contributes to resource allocation salience",
        )
        private val READINESS_FROM_HARDWARE = WorldTransferCoefficient.create(
            semanticKey = "resource-hardware-readiness-to-lane",
            sourceDimension = WorldSignalDimension.CAPABILITY_READINESS,
            targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
            multiplier = 0.60,
            maxAbsoluteContribution = 0.60,
            explanation = "Hardware capability limits every shared resource lane",
        )
        private val READINESS_FROM_STABILITY = WorldTransferCoefficient.create(
            semanticKey = "resource-hardware-stability-to-lane",
            sourceDimension = WorldSignalDimension.HEALTH_STABILITY,
            targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
            multiplier = 0.40,
            maxAbsoluteContribution = 0.40,
            explanation = "Hardware health and thermal stability limit every shared resource lane",
        )
    }
}
