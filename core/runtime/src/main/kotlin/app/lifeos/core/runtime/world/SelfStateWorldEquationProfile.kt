package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.field.world.WorldTransferCoefficient
import app.lifeos.core.runtime.self.SelfObservationDomain
import app.lifeos.core.runtime.self.SelfObservationIssueKind
import app.lifeos.core.runtime.self.SelfStateProjectionResult

enum class SelfStateWorldBand {
    STABLE,
    OBSERVE,
    DEGRADED,
    CRITICAL,
}

data class SelfStateWorldFormulaAssessment(
    val analysisId: String,
    val sourceFingerprint: String,
    val authorityFingerprint: String,
    val band: SelfStateWorldBand,
    val execution: WorldFormulaExecution,
    val controllerVector: WorldFieldVector?,
    val reasonCodes: List<String>,
) {
    init {
        require(analysisId.matches(Regex("[0-9a-f]{64}")))
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(authorityFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(reasonCodes == reasonCodes.distinct().sorted())
    }
}

/**
 * Read-only self-observation profile. It reuses the existing typed World Formula dimensions and
 * deliberately leaves unsupported dimensions absent rather than manufacturing values.
 */
class SelfStateWorldEquationProfile {
    data class PreparedRequest(
        val request: WorldFormulaRequest,
        val controllerTarget: WorldTargetRef,
        val sourceFingerprint: String,
    )

    val spec: WorldEquationSpec = WorldEquationSpec(
        version = VERSION,
        coefficients = listOf(
            HEALTH_TO_CONTROLLER,
            RUNTIME_READINESS_TO_CONTROLLER,
            RESOURCE_READINESS_TO_CONTROLLER,
            UNCERTAINTY_TO_CONTROLLER,
            SALIENCE_TO_CONTROLLER,
            CONTEXT_TO_CONTROLLER,
        ).sortedBy { it.id.value },
    )

    fun request(
        projection: SelfStateProjectionResult,
        config: WorldFormulaConfig = WorldFormulaConfig(),
    ): PreparedRequest {
        val snapshot = projection.snapshot
        val sourceFingerprint = sourceFingerprint(projection)

        val healthTarget = WorldTargetRef(WorldNodeKind.HEALTH, HEALTH_KEY)
        val runtimeTarget = WorldTargetRef(WorldNodeKind.MODULE, RUNTIME_KEY)
        val cognitionTarget = WorldTargetRef(WorldNodeKind.THOUGHT, COGNITION_KEY)
        val resourceTarget = WorldTargetRef(WorldNodeKind.RESOURCE, RESOURCE_KEY)
        val sourceTarget = WorldTargetRef(WorldNodeKind.EVIDENCE, SOURCES_KEY)
        val controllerTarget = WorldTargetRef(WorldNodeKind.CAPABILITY, CONTROLLER_KEY)

        val healthVector = vectorOf(
            dimension(
                WorldSignalDimension.HEALTH_STABILITY,
                healthStability(projection),
                sourceFingerprint,
            )
        )
        val runtimeVector = vectorOf(
            dimension(
                WorldSignalDimension.CAPABILITY_READINESS,
                runtimeReadiness(projection),
                sourceFingerprint,
            ),
            dimension(
                WorldSignalDimension.ANALYTIC_SALIENCE,
                anomalySalience(projection),
                sourceFingerprint,
            ),
        )
        val cognitionVector = vectorOf(
            dimension(
                WorldSignalDimension.UNCERTAINTY,
                cognitionUncertainty(projection),
                sourceFingerprint,
            )
        )
        val resourceVector = vectorOf(
            dimension(
                WorldSignalDimension.CAPABILITY_READINESS,
                resourceReadiness(projection),
                sourceFingerprint,
            )
        )
        val sourceVector = vectorOf(
            dimension(
                WorldSignalDimension.CONTEXT_RELEVANCE,
                liveSourceReadiness(projection),
                sourceFingerprint,
            )
        )

        val inputs = listOf(
            WorldFormulaInputSnapshot(healthTarget, healthVector, sourceFingerprint),
            WorldFormulaInputSnapshot(runtimeTarget, runtimeVector, sourceFingerprint),
            WorldFormulaInputSnapshot(cognitionTarget, cognitionVector, sourceFingerprint),
            WorldFormulaInputSnapshot(resourceTarget, resourceVector, sourceFingerprint),
            WorldFormulaInputSnapshot(sourceTarget, sourceVector, sourceFingerprint),
            WorldFormulaInputSnapshot(controllerTarget, WorldFieldVector.EMPTY, sourceFingerprint),
        )

        val interactions = listOf(
            WorldFormulaInteraction(
                source = healthTarget,
                target = controllerTarget,
                sourceDimension = WorldSignalDimension.HEALTH_STABILITY,
                targetDimension = WorldSignalDimension.HEALTH_STABILITY,
                coefficientId = HEALTH_TO_CONTROLLER.id,
                strength = 1.0,
                explanation = "self health stability constrains self-observation state",
            ),
            WorldFormulaInteraction(
                source = runtimeTarget,
                target = controllerTarget,
                sourceDimension = WorldSignalDimension.CAPABILITY_READINESS,
                targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
                coefficientId = RUNTIME_READINESS_TO_CONTROLLER.id,
                strength = 1.0,
                explanation = "runtime readiness contributes to self-observation capability",
            ),
            WorldFormulaInteraction(
                source = resourceTarget,
                target = controllerTarget,
                sourceDimension = WorldSignalDimension.CAPABILITY_READINESS,
                targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
                coefficientId = RESOURCE_READINESS_TO_CONTROLLER.id,
                strength = 1.0,
                explanation = "resource readiness contributes to self-observation capability",
            ),
            WorldFormulaInteraction(
                source = cognitionTarget,
                target = controllerTarget,
                sourceDimension = WorldSignalDimension.UNCERTAINTY,
                targetDimension = WorldSignalDimension.UNCERTAINTY,
                coefficientId = UNCERTAINTY_TO_CONTROLLER.id,
                strength = 1.0,
                explanation = "missing cognitive authority evidence contributes explicit uncertainty",
            ),
            WorldFormulaInteraction(
                source = runtimeTarget,
                target = controllerTarget,
                sourceDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
                targetDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
                coefficientId = SALIENCE_TO_CONTROLLER.id,
                strength = 1.0,
                explanation = "self-observation anomalies contribute analytic salience",
            ),
            WorldFormulaInteraction(
                source = sourceTarget,
                target = controllerTarget,
                sourceDimension = WorldSignalDimension.CONTEXT_RELEVANCE,
                targetDimension = WorldSignalDimension.CONTEXT_RELEVANCE,
                coefficientId = CONTEXT_TO_CONTROLLER.id,
                strength = 1.0,
                explanation = "available live-source context contributes self-observation context readiness",
            ),
        )

        return PreparedRequest(
            request = WorldFormulaRequest(
                inputs = inputs,
                interactions = interactions,
                equationVersion = VERSION,
                observedAt = snapshot.capturedAt,
                config = config,
            ),
            controllerTarget = controllerTarget,
            sourceFingerprint = sourceFingerprint,
        )
    }

    private fun sourceFingerprint(projection: SelfStateProjectionResult): String {
        val snapshot = projection.snapshot
        return StableFieldIds.fingerprint(
            "lifeos-self-world-input/v1",
            snapshot.authorityFingerprint,
            "health=" + encode(healthStability(projection)),
            "runtime=" + encode(runtimeReadiness(projection)),
            "resource=" + encode(resourceReadiness(projection)),
            "uncertainty=" + encode(cognitionUncertainty(projection)),
            "salience=" + encode(anomalySalience(projection)),
            "sources=" + encode(liveSourceReadiness(projection)),
            *projection.issues
                .sortedWith(compareBy({ it.domain.name }, { it.kind.name }, { it.detail }))
                .map { "issue:${it.domain.name}:${it.kind.name}:${it.detail}" }
                .toTypedArray(),
        )
    }

    private fun healthStability(projection: SelfStateProjectionResult): Double? {
        val health = projection.snapshot.health
        val values = listOf(
            health.healthy,
            health.degraded,
            health.unhealthy,
            health.recovering,
            health.quarantined,
            health.disabled,
            health.unknown,
        )
        if (values.any { it == null }) return null
        val total = values.filterNotNull().sum()
        if (total <= 0) return null
        val stable =
            requireNotNull(health.healthy).toDouble() +
                requireNotNull(health.recovering).toDouble() * 0.60 +
                requireNotNull(health.degraded).toDouble() * 0.35 +
                requireNotNull(health.unknown).toDouble() * 0.75
        return (stable / total.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun runtimeReadiness(projection: SelfStateProjectionResult): Double? {
        val runtime = projection.snapshot.runtime
        val registered = runtime.registeredSubsystems ?: return null
        if (registered.isEmpty()) return null
        val active = runtime.operationalSubsystems?.size ?: return null
        val degraded = runtime.degradedSubsystems?.size ?: return null
        val topologyReadiness = ((active + degraded * 0.5) / registered.size.toDouble()).coerceIn(0.0, 1.0)
        val heap = runtime.telemetry?.heapHeadroom()
        return if (heap == null) topologyReadiness else ((topologyReadiness + heap) / 2.0).coerceIn(0.0, 1.0)
    }

    private fun resourceReadiness(projection: SelfStateProjectionResult): Double? {
        val resource = projection.snapshot.resource
        resource.capabilityReadiness?.let { return it }
        val values = listOfNotNull(
            resource.memoryHeadroom,
            resource.storageHeadroom,
            resource.thermalHeadroom,
            resource.energyAvailability,
        )
        return values.takeIf { it.isNotEmpty() }?.average()?.coerceIn(0.0, 1.0)
    }

    private fun cognitionUncertainty(projection: SelfStateProjectionResult): Double? {
        val world = projection.snapshot.world
        val missingAuthority = listOf(
            world.worldHeadFingerprint,
            world.worldEquationFingerprint,
            world.cognitiveSnapshotFingerprint,
        ).count { it == null }
        val authorityUncertainty = missingAuthority / 3.0
        val issueUncertainty = projection.issues
            .filter { it.domain == SelfObservationDomain.COGNITION || it.domain == SelfObservationDomain.WORLD }
            .maxOfOrNull {
                when (it.kind) {
                    SelfObservationIssueKind.CORRUPT -> 1.0
                    SelfObservationIssueKind.FAILED -> 0.75
                    SelfObservationIssueKind.UNAVAILABLE -> 0.40
                }
            } ?: 0.0
        return maxOf(authorityUncertainty, issueUncertainty).coerceIn(0.0, 1.0)
    }

    private fun anomalySalience(projection: SelfStateProjectionResult): Double? {
        val issueSalience = projection.issues.maxOfOrNull {
            when (it.kind) {
                SelfObservationIssueKind.CORRUPT -> 1.0
                SelfObservationIssueKind.FAILED -> 0.75
                SelfObservationIssueKind.UNAVAILABLE -> 0.30
            }
        }
        val health = projection.snapshot.health
        val healthSalience = when {
            (health.unhealthy ?: 0) > 0 || (health.quarantined ?: 0) > 0 || (health.disabled ?: 0) > 0 -> 1.0
            (health.degraded ?: 0) > 0 -> 0.60
            (health.recovering ?: 0) > 0 -> 0.45
            health.healthy != null -> 0.0
            else -> null
        }
        return listOfNotNull(issueSalience, healthSalience).maxOrNull()
    }

    private fun liveSourceReadiness(projection: SelfStateProjectionResult): Double? {
        val sources = projection.snapshot.liveSources
        val total = sources.sourceCount ?: return null
        val healthy = sources.healthyCount ?: return null
        val blocked = sources.blockedCount ?: return null
        val failed = sources.failedCount ?: return null
        if (total == 0) return 1.0
        if (healthy + blocked + failed != total) return null
        return ((healthy + blocked * 0.25) / total.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun dimension(
        dimension: WorldSignalDimension,
        value: Double?,
        provenance: String,
    ): WorldDimensionValue? = value?.let {
        WorldDimensionValue(
            dimension = dimension,
            value = it.coerceIn(0.0, 1.0),
            confidence = 1.0,
            provenanceFingerprints = setOf(provenance),
        )
    }

    private fun vectorOf(vararg values: WorldDimensionValue?): WorldFieldVector =
        WorldFieldVector(values.filterNotNull())

    private fun encode(value: Double?): String =
        value?.let(java.lang.Double::toHexString) ?: "null"

    companion object {
        const val VERSION = "lifeos-world-self-observation-v1"
        const val HEALTH_KEY = "self:health"
        const val RUNTIME_KEY = "self:runtime"
        const val COGNITION_KEY = "self:cognition"
        const val RESOURCE_KEY = "self:resources"
        const val SOURCES_KEY = "self:sources"
        const val CONTROLLER_KEY = "self:controller"

        private val HEALTH_TO_CONTROLLER = WorldTransferCoefficient.create(
            semanticKey = "self-health-to-controller-health",
            sourceDimension = WorldSignalDimension.HEALTH_STABILITY,
            targetDimension = WorldSignalDimension.HEALTH_STABILITY,
            multiplier = 1.0,
            maxAbsoluteContribution = 1.0,
            explanation = "Self health stability propagates to self-observation controller health",
        )
        private val RUNTIME_READINESS_TO_CONTROLLER = WorldTransferCoefficient.create(
            semanticKey = "self-runtime-readiness-to-controller",
            sourceDimension = WorldSignalDimension.CAPABILITY_READINESS,
            targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
            multiplier = 0.50,
            maxAbsoluteContribution = 0.50,
            explanation = "Runtime readiness contributes half of self-observation capability readiness",
        )
        private val RESOURCE_READINESS_TO_CONTROLLER = WorldTransferCoefficient.create(
            semanticKey = "self-resource-readiness-to-controller",
            sourceDimension = WorldSignalDimension.CAPABILITY_READINESS,
            targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
            multiplier = 0.50,
            maxAbsoluteContribution = 0.50,
            explanation = "Resource readiness contributes half of self-observation capability readiness",
        )
        private val UNCERTAINTY_TO_CONTROLLER = WorldTransferCoefficient.create(
            semanticKey = "self-uncertainty-to-controller",
            sourceDimension = WorldSignalDimension.UNCERTAINTY,
            targetDimension = WorldSignalDimension.UNCERTAINTY,
            multiplier = 1.0,
            maxAbsoluteContribution = 1.0,
            explanation = "Self-observation uncertainty remains explicit and typed",
        )
        private val SALIENCE_TO_CONTROLLER = WorldTransferCoefficient.create(
            semanticKey = "self-salience-to-controller",
            sourceDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
            targetDimension = WorldSignalDimension.ANALYTIC_SALIENCE,
            multiplier = 1.0,
            maxAbsoluteContribution = 1.0,
            explanation = "Anomaly salience remains explicit in self-observation analysis",
        )
        private val CONTEXT_TO_CONTROLLER = WorldTransferCoefficient.create(
            semanticKey = "self-context-to-controller",
            sourceDimension = WorldSignalDimension.CONTEXT_RELEVANCE,
            targetDimension = WorldSignalDimension.CONTEXT_RELEVANCE,
            multiplier = 1.0,
            maxAbsoluteContribution = 1.0,
            explanation = "Available source context contributes to self-observation context readiness",
        )
    }
}

class SelfStateWorldFormulaEvaluator(
    private val profile: SelfStateWorldEquationProfile,
    private val coordinator: WorldFormulaCoordinator,
) {
    suspend fun evaluate(projection: SelfStateProjectionResult): SelfStateWorldFormulaAssessment {
        val prepared = profile.request(projection)
        val execution = coordinator.evaluate(prepared.request)
        val controllerNodeId = prepared.request.buildGraph()
            .stableNodes()
            .single { it.target == prepared.controllerTarget }
            .id
        val controller = execution.snapshot?.finalState?.get(controllerNodeId)
        val reasons = reasonCodes(projection, execution, controller)
        val band = classify(projection, execution, controller, reasons)
        val analysisId = StableFieldIds.fingerprint(
            "lifeos-self-world-assessment/v1",
            prepared.sourceFingerprint,
            projection.snapshot.authorityFingerprint,
            profile.spec.fingerprint(),
            execution.status.name,
            band.name,
            controller?.fingerprint().orEmpty(),
            *reasons.toTypedArray(),
        )
        return SelfStateWorldFormulaAssessment(
            analysisId = analysisId,
            sourceFingerprint = prepared.sourceFingerprint,
            authorityFingerprint = projection.snapshot.authorityFingerprint,
            band = band,
            execution = execution,
            controllerVector = controller,
            reasonCodes = reasons,
        )
    }

    private fun classify(
        projection: SelfStateProjectionResult,
        execution: WorldFormulaExecution,
        controller: WorldFieldVector?,
        reasons: List<String>,
    ): SelfStateWorldBand {
        if (
            execution.state != WorldFormulaExecutionState.COMPLETED ||
            execution.status == WorldFormulaStatus.INVALID_EQUATION
        ) return SelfStateWorldBand.DEGRADED

        val health = controller?.get(WorldSignalDimension.HEALTH_STABILITY)?.value
        val readiness = controller?.get(WorldSignalDimension.CAPABILITY_READINESS)?.value
        val uncertainty = controller?.get(WorldSignalDimension.UNCERTAINTY)?.value
        val salience = controller?.get(WorldSignalDimension.ANALYTIC_SALIENCE)?.value
        val context = controller?.get(WorldSignalDimension.CONTEXT_RELEVANCE)?.value

        if (
            projection.issues.any { it.kind == SelfObservationIssueKind.CORRUPT } ||
            (projection.snapshot.health.unhealthy ?: 0) > 0 ||
            (projection.snapshot.health.quarantined ?: 0) > 0 ||
            (projection.snapshot.health.disabled ?: 0) > 0 ||
            (health != null && health < 0.35) ||
            (readiness != null && readiness < 0.25)
        ) return SelfStateWorldBand.CRITICAL

        if (
            projection.issues.any { it.kind == SelfObservationIssueKind.FAILED } ||
            (projection.snapshot.health.degraded ?: 0) > 0 ||
            execution.status == WorldFormulaStatus.UNRESOLVED ||
            execution.status == WorldFormulaStatus.MAX_ITERATIONS ||
            (uncertainty != null && uncertainty >= 0.50) ||
            (health != null && health < 0.65) ||
            (readiness != null && readiness < 0.50)
        ) return SelfStateWorldBand.DEGRADED

        if (
            reasons.isNotEmpty() ||
            projection.issues.any { it.kind == SelfObservationIssueKind.UNAVAILABLE } ||
            (uncertainty != null && uncertainty >= 0.20) ||
            (health != null && health < 0.85) ||
            (readiness != null && readiness < 0.75) ||
            (context != null && context < 0.50) ||
            (salience != null && salience >= 0.35)
        ) return SelfStateWorldBand.OBSERVE

        return SelfStateWorldBand.STABLE
    }

    private fun reasonCodes(
        projection: SelfStateProjectionResult,
        execution: WorldFormulaExecution,
        controller: WorldFieldVector?,
    ): List<String> = buildList {
        if (execution.state != WorldFormulaExecutionState.COMPLETED) add("WORLD_EXECUTION_${execution.state.name}")
        if (execution.status != WorldFormulaStatus.CONVERGED) add("WORLD_STATUS_${execution.status.name}")
        projection.issues.forEach { issue ->
            add("ISSUE_" + issue.domain.name + "_" + issue.kind.name)
        }
        if ((projection.snapshot.health.unknown ?: 0) > 0) add("HEALTH_UNKNOWN_NODES")
        controller?.get(WorldSignalDimension.HEALTH_STABILITY)?.value?.let {
            if (it < 0.85) add("HEALTH_STABILITY_BELOW_STABLE")
        }
        controller?.get(WorldSignalDimension.CAPABILITY_READINESS)?.value?.let {
            if (it < 0.75) add("CAPABILITY_READINESS_BELOW_STABLE")
        }
        controller?.get(WorldSignalDimension.UNCERTAINTY)?.value?.let {
            if (it >= 0.20) add("UNCERTAINTY_ELEVATED")
        }
        controller?.get(WorldSignalDimension.ANALYTIC_SALIENCE)?.value?.let {
            if (it >= 0.35) add("ANALYTIC_SALIENCE_ELEVATED")
        }
        controller?.get(WorldSignalDimension.CONTEXT_RELEVANCE)?.value?.let {
            if (it < 0.50) add("CONTEXT_RELEVANCE_LOW")
        }
    }.distinct().sorted()
}

object SelfStateWorldFormulaRuntimeRegistry {
    @Volatile
    private var installed: SelfStateWorldFormulaEvaluator? = null

    fun install(evaluator: SelfStateWorldFormulaEvaluator) {
        installed = evaluator
    }

    fun current(): SelfStateWorldFormulaEvaluator? = installed

    fun requireCurrent(): SelfStateWorldFormulaEvaluator =
        requireNotNull(installed) { "Self-state World Formula evaluator is not installed" }
}
