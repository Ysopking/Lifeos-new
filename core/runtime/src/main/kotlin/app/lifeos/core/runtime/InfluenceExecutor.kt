package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import kotlinx.coroutines.CancellationException

data class InfluenceExecutionResult(
    val influences: List<FieldInfluence>,
    val failures: List<RuntimeFailure>,
)

class InfluenceExecutor(
    private val health: app.lifeos.core.runtime.health.RecoveryCoordinator? = null,
    private val fieldTimeoutMillis: Long = 30_000,
) {
    init { require(fieldTimeoutMillis > 0) }

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
                val timedOperation: suspend () -> FieldInfluence? = {
                    val result = kotlinx.coroutines.withTimeoutOrNull(fieldTimeoutMillis) {
                        Result.success(field.influence(photon))
                    } ?: throw java.util.concurrent.TimeoutException("Field budget exceeded")
                    result.getOrThrow()
                }
                val node = app.lifeos.core.runtime.health.HealthNode("field[$index]:${field.javaClass.name}")
                if (health != null) health.execute(node, timedOperation) else timedOperation()
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

