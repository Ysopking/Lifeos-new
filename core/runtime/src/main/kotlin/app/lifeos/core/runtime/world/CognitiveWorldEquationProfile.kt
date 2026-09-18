package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.field.FieldWorldSignalLink
import app.lifeos.core.runtime.field.FieldWorldSignalProjection
import java.time.Instant

/**
 * B161 cognitive equation profile.
 *
 * The old informational profile remains intact for replay. This profile reuses its deterministic
 * coefficient set under a new explicit physics version and requires frozen cycle/task/Photon
 * provenance. Merely building this request does not publish productive world state.
 */
class CognitiveWorldEquationProfile(
    informational: IntegratedWorldEquationProfile = IntegratedWorldEquationProfile(),
) {
    private val informationalProfile = informational

    val spec: WorldEquationSpec = informationalProfile.spec.copy(version = VERSION)

    fun request(
        projection: FieldWorldSignalProjection,
        links: List<FieldWorldSignalLink>,
        observedAt: Instant,
        cycle: WorldFormulaCycleContext,
        sourceTaskId: TaskId,
        photonId: PhotonId,
        config: WorldFormulaConfig = WorldFormulaConfig(),
    ): ProductiveWorldFormulaRequest {
        require(cycle.equationVersion == VERSION) {
            "Cognitive WorldFormula cycle must freeze $VERSION"
        }
        val base = WorldFormulaRequest(
            inputs = projection.inputs,
            interactions = informationalProfile.interactions(links),
            equationVersion = spec.version,
            observedAt = observedAt,
            config = config,
            sourceTaskId = sourceTaskId,
            photonId = photonId,
        )
        return ProductiveWorldFormulaRequest(
            request = base,
            cycle = cycle,
        )
    }

    companion object {
        const val VERSION = "lifeos-world-cognitive-v1"
    }
}
