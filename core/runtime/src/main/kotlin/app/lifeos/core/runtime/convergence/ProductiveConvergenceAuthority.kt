package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import java.time.Instant

data class ProductiveConvergenceInput(
    val cycle: BootEngineCycle,
    val workingSet: ThoughtGraphWorkingSet,
    val source: CrossDomainConvergenceRequest,
    val domainPhotons: Map<FieldDomainId, Photon>,
    val sourceTaskId: TaskId,
    val primaryPhotonId: PhotonId,
    val observedAt: Instant,
    val capabilityGaps: List<CapabilityGap> = emptyList(),
) {
    init {
        require(domainPhotons.isNotEmpty())
        require(primaryPhotonId in domainPhotons.values.mapTo(linkedSetOf()) { it.id })
    }
}

sealed interface ProductiveConvergenceResult {
    data class Decided(
        val binding: WorldFormulaBoundConvergenceRequest,
        val checkpoint: ConvergenceDecisionCheckpoint,
        val worldSnapshotId: String,
    ) : ProductiveConvergenceResult

    data class WorldNotStable(
        val binding: WorldFormulaBoundConvergenceRequest,
        val worldSnapshotId: String,
    ) : ProductiveConvergenceResult
}

fun interface ProductiveConvergenceAuthority {
    suspend fun decide(input: ProductiveConvergenceInput): ProductiveConvergenceResult
}

class DefaultProductiveConvergenceAuthority(
    private val worldBound: WorldFormulaBoundConvergenceService,
) : ProductiveConvergenceAuthority {
    override suspend fun decide(input: ProductiveConvergenceInput): ProductiveConvergenceResult =
        when (
            val result = worldBound.convergeAndDecide(
                cycle = input.cycle,
                workingSet = input.workingSet,
                source = input.source,
                domainPhotons = input.domainPhotons,
                sourceTaskId = input.sourceTaskId,
                primaryPhotonId = input.primaryPhotonId,
                observedAt = input.observedAt,
                capabilityGaps = input.capabilityGaps,
            )
        ) {
            is WorldFormulaBoundConvergenceResult.Decided ->
                ProductiveConvergenceResult.Decided(
                    binding = result.decision.binding,
                    checkpoint = result.decision.checkpoint,
                    worldSnapshotId = result.worldEvaluation.candidate.snapshot.id,
                )

            is WorldFormulaBoundConvergenceResult.WorldNotStable ->
                ProductiveConvergenceResult.WorldNotStable(
                    binding = result.binding,
                    worldSnapshotId = result.worldEvaluation.candidate.snapshot.id,
                )
        }
}
