package app.lifeos.core.runtime.boot

import app.lifeos.core.runtime.world.ProductiveWorldHeadRepository

data class WorldHeadDelta(
    val worldHeadFingerprint: String?,
    val activeCycleFingerprint: String?,
    val inconsistent: Boolean,
)

class CognitiveHeadConsistencyDeltaSource(
    private val worldHeads: ProductiveWorldHeadRepository,
    private val activeCycleFingerprint: suspend () -> String?,
) : BootDeltaSource {
    override suspend fun count(context: BootContext): Long {
        val report = worldHeads.loadReport()
        if (report.corrupted) return 1L
        val worldHead = report.head
        val cycleFingerprint = activeCycleFingerprint()
        val inconsistent =
            worldHead == null && cycleFingerprint != null
        return if (inconsistent) 1L else 0L
    }
}
