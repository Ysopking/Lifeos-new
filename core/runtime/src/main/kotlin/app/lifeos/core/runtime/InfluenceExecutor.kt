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
        completedFieldIndexes: Set<Int> = emptySet(),
        onFieldSuccess: suspend (Int) -> Unit = {},
    ): InfluenceExecutionResult {
        require(completedFieldIndexes.all { it in fields.indices }) {
            "Completed field indexes must reference the current field list"
        }

        val influences = mutableListOf<FieldInfluence>()
        val failures = mutableListOf<RuntimeFailure>()

        fields.forEachIndexed { index, field ->
            if (index in completedFieldIndexes) return@forEachIndexed

            val influence = try {
                field.influence(photon)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += RuntimeFailure(
                    category = RuntimeFailureCategory.FIELD,
                    source = "field[$index]",
                    message = error.message ?: error::class.simpleName ?: "Field failure",
                    photonId = photon.id,
                )
                return@forEachIndexed
            }

            influence?.let(influences::add)
            onFieldSuccess(index)
        }

        return InfluenceExecutionResult(
            influences = influences,
            failures = failures,
        )
    }
}
