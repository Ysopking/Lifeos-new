package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.self.SelfObservationIssue
import app.lifeos.core.runtime.self.SelfStateProjectionResult
import java.time.Instant

/**
 * B528 adapter from the read-only SelfObservation projection into the V2.2 realization grammar.
 * It creates descriptive components only; no observation is promoted into owner truth or effects.
 */
object SelfObservationRealizationAdapter {
    private val contractFrozenAt = Instant.parse("2026-09-25T00:00:00Z")

    fun profile(): RealizationTransferProfile =
        RealizationTransferProfile.create(
            version = "lifeos-self-observation-realization-v1",
            requiredComponents = listOf(
                RealizationComponentKind.PRODUCTIVE_WORLD,
                RealizationComponentKind.COGNITIVE_STATE,
                RealizationComponentKind.RESOURCE_STATE,
                RealizationComponentKind.SENSOR_STATE,
            ),
            projectionRegistryFingerprint = StableFieldIds.fingerprint(
                "self-observation-realization-projections/v1",
                "productive-world",
                "cognitive-state",
                "resource-state",
                "sensor-state",
            ),
            stateContractFingerprint = StableFieldIds.fingerprint(
                "self-observation-realization-state-contract/v1",
                "world-authority",
                "photon-memory-cognition",
                "runtime-resource-health-recovery",
                "live-source-observation",
            ),
            observableIds = listOf(
                "self.cognition",
                "self.live-sources",
                "self.resources",
                "self.world-authority",
            ),
            allowedAuxiliaryVariableIds = listOf(
                "self.health",
                "self.recovery",
                "self.runtime-topology",
                "self.tools",
            ),
            invariantIds = listOf(
                "observation-not-world-fact",
                "prediction-not-outcome",
                "truth-not-permission",
                "permission-not-execution",
            ),
            failureCriterionIds = listOf(
                "corrupt-self-observation",
                "missing-world-authority",
                "projection-underidentified",
            ),
            frozenAt = contractFrozenAt,
        )

    fun components(
        result: SelfStateProjectionResult,
    ): List<RealizationComponentRef> {
        val snapshot = result.snapshot
        val issueFingerprint = issueFingerprint(result.issues)

        val world = RealizationComponentRef(
            kind = RealizationComponentKind.PRODUCTIVE_WORLD,
            representationId = "self-world:${snapshot.stateFingerprint}",
            semanticFingerprint = StableFieldIds.fingerprint(
                "self-realization-world-semantic/v1",
                snapshot.authorityFingerprint,
                encode(snapshot.world.worldHeadRevision),
                encode(snapshot.world.worldHeadFingerprint),
                encode(snapshot.world.worldEquationRevision),
                encode(snapshot.world.worldEquationVersion),
                encode(snapshot.world.worldEquationFingerprint),
                encode(snapshot.world.bootCycleId),
                encode(snapshot.world.bootCycleFingerprint),
            ),
            provenanceFingerprints = listOfNotNull(
                snapshot.world.worldHeadFingerprint,
                snapshot.world.worldEquationFingerprint,
                snapshot.world.bootCycleFingerprint,
            ).distinct().sorted(),
        )

        val cognition = RealizationComponentRef(
            kind = RealizationComponentKind.COGNITIVE_STATE,
            representationId = "self-cognition:${snapshot.stateFingerprint}",
            semanticFingerprint = StableFieldIds.fingerprint(
                "self-realization-cognition-semantic/v1",
                encode(snapshot.photon.indexFingerprint),
                encode(snapshot.photon.headFingerprint),
                encode(snapshot.memory.memoryFingerprint),
                encode(snapshot.world.cognitiveSnapshotFingerprint),
                encode(snapshot.tools.totalTools),
                encode(snapshot.tools.activeTools),
                encode(snapshot.tools.trialTools),
                encode(snapshot.tools.quarantinedTools),
                encode(snapshot.tools.rejectedTools),
            ),
            provenanceFingerprints = listOfNotNull(
                snapshot.photon.indexFingerprint,
                snapshot.photon.headFingerprint,
                snapshot.memory.memoryFingerprint,
                snapshot.world.cognitiveSnapshotFingerprint,
            ).distinct().sorted(),
        )

        val resources = RealizationComponentRef(
            kind = RealizationComponentKind.RESOURCE_STATE,
            representationId = "self-resource:${snapshot.stateFingerprint}",
            semanticFingerprint = StableFieldIds.fingerprint(
                "self-realization-resource-semantic/v1",
                encode(snapshot.runtime.topologyFingerprint),
                encode(snapshot.resource.hardwareFingerprint),
                ratio(snapshot.resource.memoryHeadroom),
                ratio(snapshot.resource.storageHeadroom),
                ratio(snapshot.resource.thermalHeadroom),
                ratio(snapshot.resource.energyAvailability),
                ratio(snapshot.resource.capabilityReadiness),
                encode(snapshot.health.healthy),
                encode(snapshot.health.degraded),
                encode(snapshot.health.unhealthy),
                encode(snapshot.health.recovering),
                encode(snapshot.health.quarantined),
                encode(snapshot.health.disabled),
                encode(snapshot.health.unknown),
                encode(snapshot.recovery.recoveryStateFingerprint),
            ),
            provenanceFingerprints = listOfNotNull(
                snapshot.runtime.topologyFingerprint,
                snapshot.resource.hardwareFingerprint,
                snapshot.recovery.recoveryStateFingerprint,
            ).distinct().sorted(),
        )

        val sensors = RealizationComponentRef(
            kind = RealizationComponentKind.SENSOR_STATE,
            representationId = "self-sensors:${snapshot.stateFingerprint}",
            semanticFingerprint = StableFieldIds.fingerprint(
                "self-realization-sensor-semantic/v1",
                encode(snapshot.liveSources.sourceCount),
                encode(snapshot.liveSources.healthyCount),
                encode(snapshot.liveSources.blockedCount),
                encode(snapshot.liveSources.failedCount),
                encode(snapshot.liveSources.sourceStateFingerprint),
                issueFingerprint,
            ),
            provenanceFingerprints = listOfNotNull(
                snapshot.liveSources.sourceStateFingerprint,
                issueFingerprint,
            ).distinct().sorted(),
        )

        return listOf(world, cognition, resources, sensors)
            .sortedBy { it.kind.name }
    }

    fun issueFingerprint(
        issues: Collection<SelfObservationIssue>,
    ): String = StableFieldIds.fingerprint(
        "self-realization-issues/v1",
        *issues
            .sortedWith(
                compareBy<SelfObservationIssue> { it.domain.name }
                    .thenBy { it.kind.name }
                    .thenBy { it.detail }
            )
            .map { "${it.domain.name}:${it.kind.name}:${it.detail}" }
            .toTypedArray(),
    )

    private fun ratio(value: Double?): String =
        value?.let(java.lang.Double::toHexString) ?: "null"

    private fun encode(value: Any?): String = value?.toString() ?: "null"
}
