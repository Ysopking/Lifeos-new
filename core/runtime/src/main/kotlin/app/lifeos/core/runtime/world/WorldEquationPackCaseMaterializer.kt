package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldFieldVector

data class WorldEquationPackMaterializedCase(
    val request: WorldFormulaRequest?,
    val materializationFingerprint: String,
) {
    init {
        require(materializationFingerprint.isNotBlank())
    }
}

/**
 * Canonical structural materialization used by isolated canary execution.
 *
 * The same frozen case is projected through the candidate pack contract before evaluation. This
 * class carries no persistence, activation or ProductiveWorldHead authority.
 */
class WorldEquationPackCaseMaterializer {
    fun materialize(
        pack: WorldEquationPack,
        case: WorldEquationPackShadowCase,
    ): WorldEquationPackMaterializedCase {
        val selected = case.request.inputs.mapNotNull { input ->
            val inputFingerprint = input.fingerprint()
            val providerId = case.providerIdByInputFingerprint.getValue(inputFingerprint)
            if (providerId !in pack.projectionContract.requiredProviderIds) return@mapNotNull null
            if (input.target.kind !in pack.requiredNodeKinds) return@mapNotNull null

            val pruned = WorldFieldVector(
                input.vector.stableValues()
                    .filter { it.dimension in pack.requiredDimensions }
            )
            providerId to input.copy(vector = pruned)
        }

        val inputs = selected.map { it.second }
        val byTarget = inputs.associateBy { it.target }
        val schemaByCoefficient = pack.interactionSchema.stableEntries()
            .associateBy { it.coefficientId }

        val interactions = case.request.interactions.filter { interaction ->
            val source = byTarget[interaction.source] ?: return@filter false
            val target = byTarget[interaction.target] ?: return@filter false
            val schema = schemaByCoefficient[interaction.coefficientId] ?: return@filter false
            val coefficient = pack.equation.coefficient(interaction.coefficientId) ?: return@filter false

            source.target.kind in schema.sourceNodeKinds &&
                target.target.kind in schema.targetNodeKinds &&
                interaction.sourceDimension in pack.requiredDimensions &&
                interaction.targetDimension in pack.requiredDimensions &&
                coefficient.sourceDimension == interaction.sourceDimension &&
                coefficient.targetDimension == interaction.targetDimension
        }.sortedBy { it.fingerprint() }

        val materializationFingerprint = StableFieldIds.fingerprint(
            "world-equation-pack-materialization/v1",
            *buildList {
                selected.sortedBy { it.second.fingerprint() }.forEach { pair ->
                    add("input:" + pair.first + ":" + pair.second.fingerprint())
                }
                interactions.forEach { add("interaction:" + it.fingerprint()) }
            }.toTypedArray(),
        )

        val request = if (inputs.isEmpty()) {
            null
        } else {
            case.request.copy(
                inputs = inputs.sortedBy { it.fingerprint() },
                interactions = interactions,
                equationVersion = pack.equation.version,
            )
        }
        return WorldEquationPackMaterializedCase(
            request = request,
            materializationFingerprint = materializationFingerprint,
        )
    }
}
