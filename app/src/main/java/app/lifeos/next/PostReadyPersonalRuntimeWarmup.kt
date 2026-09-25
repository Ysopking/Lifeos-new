package app.lifeos.next

import app.lifeos.core.runtime.CognitiveSnapshotRuntimeRegistry
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntime
import app.lifeos.core.runtime.life.FuturePlanningCoordinator
import app.lifeos.next.kernel.CanonicalLifePhotonRepository
import app.lifeos.next.kernel.CanonicalPhotonIngress
import java.time.Instant

internal class PostReadyPersonalRuntimeWarmup(
    private val photons: CanonicalLifePhotonRepository,
    private val memory: DurableLifeMemoryRuntime,
    private val futurePlanning: FuturePlanningCoordinator,
    private val ingress: CanonicalPhotonIngress,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun run() {
        photons.reconcilePersisted()
        memory.rebuild(now())
        CognitiveSnapshotRuntimeRegistry.captureLatest()
        futurePlanning.reconsiderAll().forEach { planned ->
            ingress.ingest(
                planned,
                PhotonIngressMode.DERIVED,
            )
        }
    }
}
