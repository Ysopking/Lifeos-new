package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import kotlinx.coroutines.CancellationException

data class InfluenceExecutionResult(
    val influences: List<FieldInfluence>,
    val failures: List<RuntimeFailure>,
)

class InfluenceExecutor {
    suspend fun execute(
        photon: Photon,
        fields: List<ForceField>,
    ): InfluenceExecutionResult {
        val influences = mutableListOf<FieldInfluence>()
        val failures = mutableListOf<RuntimeFailure>()

        fields.forEachIndexed { index, field ->
            try {
                field.influence(photon)?.let(influences::add)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += RuntimeFailure(
                    category = RuntimeFailureCategory.FIELD,
                    source = "field[$index]",
                    message = error.message ?: error::class.simpleName ?: "Field failure",
                    photonId = photon.id,
                )
            }
        }

        return InfluenceExecutionResult(
            influences = influences,
            failures = failures,
        )
    }
}
