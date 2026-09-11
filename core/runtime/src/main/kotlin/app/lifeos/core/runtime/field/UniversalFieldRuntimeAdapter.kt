package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldSnapshotRepository
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.health.HealthGate
import app.lifeos.core.runtime.health.HealthGatePermit
import app.lifeos.core.runtime.health.HealthGateResult
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Executes the universal field engine as an observational shadow stage.
 *
 * A regular projection/enrichment/convergence/persistence/health error is represented in
 * [FieldShadowExecution] and is never promoted to a durable task failure by this adapter.
 * Cancellation still propagates so task lease/lifecycle cancellation remains authoritative.
 */
class UniversalFieldRuntimeAdapter(
    private val snapshotRepository: FieldSnapshotRepository,
    private val requestFactory: PhotonFieldRequestFactory = DefaultPhotonFieldRequestFactory(),
    private val requestEnricher: FieldRequestEnricher = FieldRequestEnricher.NONE,
    private val engine: FieldConvergenceEngine = FieldConvergenceEngine(),
    private val healthGate: HealthGate? = null,
    private val thoughtGraphProjection: FieldThoughtGraphProjectionCoordinator? = null,
    private val now: () -> Instant = Instant::now,
) : FieldShadowProcessor {
    override suspend fun process(photon: Photon): FieldShadowExecution {
        val baseRequest = try {
            requestFactory.create(photon)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return FieldShadowExecution.failed(
                message = error.message ?: "universal-field-request-projection-failed",
                source = photon,
            )
        }
        val request = try {
            requestEnricher.enrich(baseRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return FieldShadowExecution.failed(
                domainId = baseRequest.domainId,
                message = error.message ?: "universal-field-request-enrichment-failed",
                source = photon,
            )
        }

        var permit: HealthGatePermit? = null
        if (healthGate != null) {
            when (val admission = healthGate.acquire(universalFieldShadowHealthNodeId(request.domainId), now())) {
                is HealthGateResult.Granted -> permit = admission.permit
                is HealthGateResult.BlockedByProtection -> return FieldShadowExecution.blocked(
                    domainId = request.domainId,
                    message = "protection:${admission.state.mode.name.lowercase()}:generation-${admission.state.generation}",
                    source = photon,
                )
                is HealthGateResult.BlockedByQuarantine -> return FieldShadowExecution.blocked(
                    domainId = request.domainId,
                    message = "quarantined:${admission.entry.nodeId.value}",
                    source = photon,
                )
                is HealthGateResult.BlockedByCircuit -> return FieldShadowExecution.blocked(
                    domainId = request.domainId,
                    message = "circuit:${admission.state.name.lowercase()}",
                    source = photon,
                )
            }
        }

        return try {
            val result = engine.converge(request)
            // Crash-safe write order: projection intent first, authoritative snapshot second,
            // graph materialization last. A kill at any boundary is repaired by boot reconciliation.
            val projection = thoughtGraphProjection?.prepare(photon, request, result)
            snapshotRepository.save(result.snapshot)
            if (projection != null) thoughtGraphProjection.materialize(projection)
            if (permit != null && healthGate != null) {
                try {
                    healthGate.onSuccess(permit)
                } catch (_: Exception) {
                    // Health reporting cannot invalidate an already persisted observational snapshot.
                }
            }
            FieldShadowExecution(
                state = FieldShadowState.COMPLETED,
                domainId = request.domainId,
                runId = result.state.runId,
                snapshotId = result.snapshot.id,
                convergenceStatus = result.status,
                sourcePhotonId = photon.id,
                sourceRevision = photon.revision,
                sourceFingerprint = runtimePhotonFingerprint(photon),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (permit != null && healthGate != null) {
                try {
                    healthGate.onFailure(permit, now())
                } catch (_: Exception) {
                    // Shadow health reporting remains best-effort and cannot hide the root failure.
                }
            }
            FieldShadowExecution.failed(
                domainId = request.domainId,
                message = error.message ?: error::class.simpleName ?: "universal-field-shadow-failed",
                source = photon,
            )
        }
    }
}
