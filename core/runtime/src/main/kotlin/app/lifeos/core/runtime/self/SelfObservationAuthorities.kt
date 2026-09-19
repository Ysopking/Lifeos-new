package app.lifeos.core.runtime.self

import app.lifeos.core.runtime.CognitiveSnapshot
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.WorldEquationHead

data class SelfObservationAuthoritySnapshot(
    val productiveWorldHead: ProductiveWorldHead?,
    val worldEquationHead: WorldEquationHead?,
    val committedBootCycle: BootEngineCycle?,
    val cognitiveSnapshot: CognitiveSnapshot?,
)

interface SelfObservationAuthorityReader {
    suspend fun loadProductiveWorldHead(): ProductiveWorldHead?
    suspend fun loadWorldEquationHead(): WorldEquationHead?
    suspend fun loadCommittedBootCycle(): BootEngineCycle?
    suspend fun loadCognitiveSnapshot(): CognitiveSnapshot?

    suspend fun snapshot(): SelfObservationAuthoritySnapshot = SelfObservationAuthoritySnapshot(
        productiveWorldHead = loadProductiveWorldHead(),
        worldEquationHead = loadWorldEquationHead(),
        committedBootCycle = loadCommittedBootCycle(),
        cognitiveSnapshot = loadCognitiveSnapshot(),
    )
}

/** Read-only process seam over existing durable authorities. It cannot publish or mutate heads. */
object SelfObservationAuthorityRuntimeRegistry {
    private val slot =
        app.lifeos.core.runtime.process.NonOwningRuntimeSlot<SelfObservationAuthorityReader>(
            "Self-observation authority reader"
        )

    fun install(reader: SelfObservationAuthorityReader) {
        slot.install(reader)
    }

    fun current(): SelfObservationAuthorityReader? = slot.currentOrNull()

    fun requireCurrent(): SelfObservationAuthorityReader =
        slot.currentOrNull() ?: error("Self-observation authority reader is not installed")

    internal fun clearForTests() {
        slot.clear()
    }
}
