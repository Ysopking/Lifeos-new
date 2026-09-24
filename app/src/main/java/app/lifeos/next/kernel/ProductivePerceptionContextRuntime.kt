package app.lifeos.next.kernel

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.AppSensorRegistrySnapshot
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import app.lifeos.core.runtime.world.PersonalContextBootBinding
import app.lifeos.core.runtime.world.PersonalContextSnapshot
import app.lifeos.core.runtime.world.SensorAttentionDecision
import app.lifeos.core.runtime.world.SensorAttentionDemand
import app.lifeos.core.runtime.world.SensorAttentionRuntime

/**
 * B469 process-local productive perception composition.
 *
 * It owns only sensor scheduling metadata and the exact PersonalContext boot binding. Observation
 * permission remains in OwnerObservationPolicyLedger; effect authority remains outside this runtime.
 */
internal fun interface PersonalContextBootBindingSource {
    suspend fun freeze(
        workingSet: ThoughtGraphWorkingSet,
    ): PersonalContextBootBinding
}

internal class ProductivePerceptionContextRuntime(
    private val ownerObservationPolicy: OwnerObservationPolicyLedger,
    private val sensorRegistry: AppSensorRegistry = AppSensorRegistry(),
) : PersonalContextBootBindingSource {
    private val attentionRuntime = SensorAttentionRuntime(sensorRegistry)
    private var hardwareBridge: AndroidHardwareSensorBridge? = null

    /**
     * Registers the concrete bridge contract without starting physical acquisition.
     * Productive acquisition begins only after kernel readiness via [start].
     */
    suspend fun attachHardwareBridge(
        bridge: AndroidHardwareSensorBridge,
    ) {
        val current = hardwareBridge
        require(current == null || current === bridge) {
            "Productive perception runtime cannot replace an attached hardware bridge"
        }
        sensorRegistry.register(bridge.descriptor)
        hardwareBridge = bridge
    }

    /**
     * Applies the registry's default attention policy after the kernel is ready.
     */
    suspend fun start(): List<SensorAttentionDecision> {
        requireNotNull(hardwareBridge) {
            "Productive perception runtime requires the hardware bridge before start"
        }
        return applyAttention(emptyList())
    }

    /**
     * B459 is the only scheduler here. Decisions update process-local registry metadata first and
     * are then applied to the physical Android bridge. They cannot create observation grants.
     */
    suspend fun applyAttention(
        demands: Collection<SensorAttentionDemand>,
    ): List<SensorAttentionDecision> {
        val decisions = attentionRuntime.apply(demands)
        val bridge = hardwareBridge
        if (bridge != null) {
            decisions
                .firstOrNull { it.sensorId == bridge.descriptor.sensorId }
                ?.let { decision -> bridge.applyAttention(decision.mode) }
        }
        return decisions
    }

    suspend fun sensorRegistrySnapshot(): AppSensorRegistrySnapshot =
        sensorRegistry.snapshot()

    /**
     * Freezes the actual cycle-local perception lineage into B479's BootEngine binding.
     *
     * No placeholder authority is manufactured: the sensor fingerprint comes from the live
     * registry and the policy revision comes from the durable Owner Observation Policy head.
     */
    override suspend fun freeze(
        workingSet: ThoughtGraphWorkingSet,
    ): PersonalContextBootBinding {
        val sensors = sensorRegistry.snapshot()
        val policy = ownerObservationPolicy.snapshot()

        val observationHeadFingerprint = StableFieldIds.fingerprint(
            "personal-context-observation-head/v1",
            workingSet.sourceSnapshotId,
            workingSet.sourceRevision.toString(),
            workingSet.sourceHistoryFingerprint,
        )
        val evidenceHeadFingerprint = StableFieldIds.fingerprint(
            "personal-context-evidence-head/v1",
            workingSet.sourceHistoryFingerprint,
            workingSet.sourceSnapshotId,
        )
        val sensorFingerprint = sensors.fingerprint()

        val context = PersonalContextSnapshot.create(
            appObservationHeadFingerprint = observationHeadFingerprint,
            sensorProjectionFingerprint = sensorFingerprint,
            evidenceHeadFingerprint = evidenceHeadFingerprint,
            ownerObservationPolicyRevision = policy.revision,
            createdAt = workingSet.asOf,
        )

        return PersonalContextBootBinding(
            personalContextSnapshotId = context.id,
            sensorRegistryFingerprint = sensorFingerprint,
            ownerObservationPolicyRevision = policy.revision,
        )
    }
}
