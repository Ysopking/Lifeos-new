package app.lifeos.core.runtime.health

import java.time.Instant
import java.util.concurrent.CancellationException

enum class RepairProbeKind {
    STORE,
    WORKER,
    FIELD,
    CAPABILITY_TOOL,
}

enum class RepairProbeStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    UNAVAILABLE,
}

data class RepairProbeFact(
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank()) { "Repair probe fact key must not be blank" }
        require(key.length <= 96) { "Repair probe fact key too long" }
        require(value.length <= 512) { "Repair probe fact value too long" }
    }
}

data class RepairProbeObservation(
    val status: RepairProbeStatus,
    val message: String? = null,
    val facts: List<RepairProbeFact> = emptyList(),
) {
    init {
        require(message == null || message.length <= 512) { "Repair probe message too long" }
        require(facts.size <= 32) { "Too many repair probe facts" }
        require(facts.map { it.key }.distinct().size == facts.size) {
            "Repair probe fact keys must be unique"
        }
    }
}

interface RepairProbe {
    val id: String
    val kind: RepairProbeKind
    val nodeId: HealthNodeId

    suspend fun observe(): RepairProbeObservation
}

data class RepairProbeResult(
    val probeId: String,
    val kind: RepairProbeKind,
    val nodeId: HealthNodeId,
    val status: RepairProbeStatus,
    val observedAt: Instant,
    val message: String? = null,
    val facts: List<RepairProbeFact> = emptyList(),
) {
    init {
        require(probeId.isNotBlank()) { "Repair probe id must not be blank" }
        require(probeId.length <= 128) { "Repair probe id too long" }
        require(facts == facts.sortedWith(compareBy(RepairProbeFact::key, RepairProbeFact::value))) {
            "Repair probe facts must use canonical ordering"
        }
    }

    val verifiedHealthy: Boolean
        get() = status == RepairProbeStatus.HEALTHY
}

abstract class ComponentRepairProbe(
    final override val id: String,
    final override val kind: RepairProbeKind,
    final override val nodeId: HealthNodeId,
    private val check: suspend () -> RepairProbeObservation,
) : RepairProbe {
    init {
        require(id.isNotBlank()) { "Repair probe id must not be blank" }
        require(id.length <= 128) { "Repair probe id too long" }
    }

    final override suspend fun observe(): RepairProbeObservation = check()
}

class StoreRepairProbe(
    id: String,
    nodeId: HealthNodeId,
    check: suspend () -> RepairProbeObservation,
) : ComponentRepairProbe(id, RepairProbeKind.STORE, nodeId, check)

class WorkerRepairProbe(
    id: String,
    nodeId: HealthNodeId,
    check: suspend () -> RepairProbeObservation,
) : ComponentRepairProbe(id, RepairProbeKind.WORKER, nodeId, check)

class FieldRepairProbe(
    id: String,
    nodeId: HealthNodeId,
    check: suspend () -> RepairProbeObservation,
) : ComponentRepairProbe(id, RepairProbeKind.FIELD, nodeId, check)

class CapabilityToolRepairProbe(
    id: String,
    nodeId: HealthNodeId,
    check: suspend () -> RepairProbeObservation,
) : ComponentRepairProbe(id, RepairProbeKind.CAPABILITY_TOOL, nodeId, check)

data class CompositeRepairEvidence(
    val targetNodeId: HealthNodeId,
    val capturedAt: Instant,
    val results: List<RepairProbeResult>,
) {
    init {
        require(results.isNotEmpty()) { "Composite repair evidence must contain results" }
        require(results == results.sortedBy { it.probeId }) {
            "Repair probe results must use canonical ordering"
        }
        require(results.map { it.probeId }.distinct().size == results.size) {
            "Repair probe ids must be unique"
        }
    }

    val verifiedHealthy: Boolean
        get() = results.all(RepairProbeResult::verifiedHealthy)

    val worstStatus: RepairProbeStatus
        get() = requireNotNull(results.maxByOrNull(::repairProbeSeverity)).status

    fun summary(): String = results.joinToString(separator = ";") {
        "${it.probeId}:${it.status.name.lowercase()}"
    }
}

class CompositeRepairProbe(
    probes: List<RepairProbe>,
    private val now: () -> Instant = Instant::now,
) {
    private val probes = probes.sortedBy { it.id }

    init {
        require(probes.isNotEmpty()) { "Composite repair probe must contain probes" }
        require(probes.map { it.id }.distinct().size == probes.size) {
            "Repair probe ids must be unique"
        }
    }

    suspend fun collect(targetNodeId: HealthNodeId): CompositeRepairEvidence {
        val capturedAt = now()
        val results = probes.map { probe ->
            val observation = try {
                probe.observe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RepairProbeObservation(
                    status = RepairProbeStatus.UNAVAILABLE,
                    message = "probe-exception:${error.javaClass.simpleName}",
                )
            }
            RepairProbeResult(
                probeId = probe.id,
                kind = probe.kind,
                nodeId = probe.nodeId,
                status = observation.status,
                observedAt = capturedAt,
                message = observation.message,
                facts = observation.facts.sortedWith(compareBy(RepairProbeFact::key, RepairProbeFact::value)),
            )
        }
        return CompositeRepairEvidence(
            targetNodeId = targetNodeId,
            capturedAt = capturedAt,
            results = results,
        )
    }
}

private fun repairProbeSeverity(result: RepairProbeResult): Int = when (result.status) {
    RepairProbeStatus.HEALTHY -> 0
    RepairProbeStatus.DEGRADED -> 1
    RepairProbeStatus.UNHEALTHY -> 2
    RepairProbeStatus.UNAVAILABLE -> 3
}
