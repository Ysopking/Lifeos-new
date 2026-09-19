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
    @Volatile
    private var installed: SelfObservationAuthorityReader? = null

    fun install(reader: SelfObservationAuthorityReader) {
        installed = reader
    }

    fun current(): SelfObservationAuthorityReader? = installed

    fun requireCurrent(): SelfObservationAuthorityReader =
        requireNotNull(installed) { "Self-observation authority reader is not installed" }
}
