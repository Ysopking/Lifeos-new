package app.lifeos.core.runtime.health

import app.lifeos.core.model.health.RuntimeProtectionState

/**
 * Conservative C02 verifier. It only releases protection when every explicitly affected node is
 * already observed HEALTHY. Global safe mode without affected nodes remains locked until C03
 * installs active component repair probes.
 */
class HealthGraphProtectionResumeVerifier(
    private val graph: HealthGraph,
) : ProtectionResumeVerifier {
    override suspend fun verify(state: RuntimeProtectionState): ProtectionVerificationResult {
        if (state.affectedNodes.isEmpty()) {
            return ProtectionVerificationResult.Rejected(
                "Active component repair probes are required for global protection",
            )
        }
        val snapshot = graph.snapshot()
        val nodes = snapshot.nodes.associateBy { it.id.value }
        val unhealthy = state.affectedNodes.filter { ref ->
            nodes[ref.value]?.state != HealthState.HEALTHY
        }
        return if (unhealthy.isEmpty()) {
            ProtectionVerificationResult.Verified("All affected nodes are observed healthy")
        } else {
            ProtectionVerificationResult.Rejected(
                "Affected nodes are not verified healthy: ${unhealthy.joinToString(",") { it.value }}",
            )
        }
    }
}
