package app.lifeos.core.runtime.escalation

import app.lifeos.core.runtime.health.HealthNodeId

/**
 * Process-wide access point for the single productive central escalation authority.
 *
 * Producers may submit typed escalation triggers through this port, but they do not own policy,
 * durable transitions, or level execution semantics. Android installs the productive coordinator
 * after its durable ledger and subsystem executors have been composed.
 */
object EscalationRuntimeRegistry {
    private val slot =
        app.lifeos.core.runtime.process.NonOwningRuntimeSlot<EscalationCoordinator>(
            "Central escalation runtime"
        )

    fun install(coordinator: EscalationCoordinator) {
        slot.install(coordinator)
    }

    fun currentOrNull(): EscalationCoordinator? = slot.currentOrNull()

    fun requireCurrent(): EscalationCoordinator =
        slot.currentOrNull() ?: error("Central escalation runtime is not installed")

    suspend fun coordinate(trigger: EscalationTrigger): EscalationCoordinationResult =
        requireCurrent().coordinate(trigger)

    suspend fun resumeActive(nodeId: HealthNodeId): List<EscalationCoordinationResult> =
        requireCurrent().resumeActive(nodeId)

    internal fun clearForTests() {
        slot.clear()
    }
}
