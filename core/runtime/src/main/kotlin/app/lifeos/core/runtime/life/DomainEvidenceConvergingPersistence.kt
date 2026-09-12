package app.lifeos.core.runtime.life

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.CausalDerivedPhotonPersistence

class DomainEvidenceConvergingPersistence(
    private val delegate: CausalDerivedPhotonPersistence,
    private val convergence: DomainEvidenceConvergenceCoordinator,
) : CausalDerivedPhotonPersistence {
    override suspend fun persist(photon: Photon, traceId: CausalTraceId) {
        delegate.persist(photon, traceId)
        convergence.convergePersisted(photon)?.let { converged ->
            delegate.persist(converged, traceId)
        }
    }
}
