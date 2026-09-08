package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.field.FieldShadowExecution
import app.lifeos.core.runtime.field.FieldShadowState
import java.security.MessageDigest
import java.time.Instant

@JvmInline
value class RepairProbeId(val value: String) {
    init { require(value.isNotBlank()) { "Repair probe id must not be blank" } }
}

enum class RepairProbeKind {
    STORE,
    WORKER,
    FIELD,
    CAPABILITY_TOOL,
}

enum class RepairProbeStatus {
    PASSED,
    FAILED,
    BLOCKED,
}

data class RepairEvidence(
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank()) { "Repair evidence key must not be blank" }
        require(value.isNotBlank()) { "Repair evidence value must not be blank" }
    }
}

data class RepairOperationResult(
    val healthy: Boolean,
    val evidence: List<RepairEvidence> = emptyList(),
    val message: String? = null,
)

data class RepairProbeResult(
    val probeId: RepairProbeId,
    val kind: RepairProbeKind,
    val targetNodes: Set<HealthNodeId>,
    val status: RepairProbeStatus,
    val evidence: List<RepairEvidence>,
    val message: String?,
    val observedAt: Instant,
) {
    init {
        require(targetNodes.isNotEmpty()) { "Repair probe result requires a target" }
        require(status == RepairProbeStatus.PASSED || !message.isNullOrBlank()) {
            "Non-passing repair probe requires a message"
        }
    }

    val fingerprint: String by lazy {
        val canonical = buildString {
            append(probeId.value).append('|').append(kind.name).append('|').append(status.name)
            targetNodes.sortedBy { it.value }.forEach { append('|').append(it.value) }
            evidence.sortedWith(compareBy<RepairEvidence>({ it.key }, { it.value })).forEach {
                append('|').append(it.key).append('=').append(it.value)
            }
            append('|').append(message.orEmpty()).append('|').append(observedAt)
        }
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

interface RepairProbe {
    val id: RepairProbeId
    val kind: RepairProbeKind
    val targetNodes: Set<HealthNodeId>
    suspend fun run(): RepairProbeResult
}

private abstract class OperationRepairProbe(
    final override val id: RepairProbeId,
    final override val kind: RepairProbeKind,
    final override val targetNodes: Set<HealthNodeId>,
    private val operation: suspend () -> RepairOperationResult,
    private val now: () -> Instant,
) : RepairProbe {
    init { require(targetNodes.isNotEmpty()) { "Repair probe requires target nodes" } }

    final override suspend fun run(): RepairProbeResult {
        val observedAt = now()
        return try {
            val result = operation()
            RepairProbeResult(
                probeId = id,
                kind = kind,
                targetNodes = targetNodes,
                status = if (result.healthy) RepairProbeStatus.PASSED else RepairProbeStatus.FAILED,
                evidence = result.evidence,
                message = result.message,
                observedAt = observedAt,
            )
        } catch (error: Exception) {
            RepairProbeResult(
                probeId = id,
                kind = kind,
                targetNodes = targetNodes,
                status = RepairProbeStatus.FAILED,
                evidence = listOf(
                    RepairEvidence("exception", error::class.simpleName ?: "Exception"),
                ),
                message = error.message ?: "repair probe failed",
                observedAt = observedAt,
            )
        }
    }
}

class StoreRepairProbe(
    id: RepairProbeId,
    targetNodes: Set<HealthNodeId>,
    operation: suspend () -> RepairOperationResult,
    now: () -> Instant = Instant::now,
) : OperationRepairProbe(id, RepairProbeKind.STORE, targetNodes, operation, now)

class WorkerRepairProbe(
    id: RepairProbeId,
    targetNodes: Set<HealthNodeId>,
    operation: suspend () -> RepairOperationResult,
    now: () -> Instant = Instant::now,
) : OperationRepairProbe(id, RepairProbeKind.WORKER, targetNodes, operation, now)

class CapabilityToolRepairProbe(
    id: RepairProbeId,
    targetNodes: Set<HealthNodeId>,
    operation: suspend () -> RepairOperationResult,
    now: () -> Instant = Instant::now,
) : OperationRepairProbe(id, RepairProbeKind.CAPABILITY_TOOL, targetNodes, operation, now)

class FieldRepairProbe(
    override val id: RepairProbeId,
    override val targetNodes: Set<HealthNodeId>,
    private val operation: suspend () -> FieldShadowExecution,
    private val now: () -> Instant = Instant::now,
) : RepairProbe {
    override val kind: RepairProbeKind = RepairProbeKind.FIELD

    init { require(targetNodes.isNotEmpty()) { "Field repair probe requires target nodes" } }

    override suspend fun run(): RepairProbeResult {
        val observedAt = now()
        return try {
            val shadow = operation()
            when (shadow.state) {
                FieldShadowState.COMPLETED -> RepairProbeResult(
                    probeId = id,
                    kind = kind,
                    targetNodes = targetNodes,
                    status = RepairProbeStatus.PASSED,
                    evidence = listOfNotNull(
                        shadow.runId?.let { RepairEvidence("runId", it.value) },
                        shadow.snapshotId?.let { RepairEvidence("snapshotId", it.value) },
                        shadow.convergenceStatus?.let { RepairEvidence("convergence", it.name) },
                    ),
                    message = null,
                    observedAt = observedAt,
                )
                FieldShadowState.BLOCKED -> RepairProbeResult(
                    probeId = id,
                    kind = kind,
                    targetNodes = targetNodes,
                    status = RepairProbeStatus.BLOCKED,
                    evidence = emptyList(),
                    message = shadow.message ?: "field repair probe blocked",
                    observedAt = observedAt,
                )
                FieldShadowState.FAILED -> RepairProbeResult(
                    probeId = id,
                    kind = kind,
                    targetNodes = targetNodes,
                    status = RepairProbeStatus.FAILED,
                    evidence = emptyList(),
                    message = shadow.message ?: "field repair probe failed",
                    observedAt = observedAt,
                )
            }
        } catch (error: Exception) {
            RepairProbeResult(
                probeId = id,
                kind = kind,
                targetNodes = targetNodes,
                status = RepairProbeStatus.FAILED,
                evidence = listOf(RepairEvidence("exception", error::class.simpleName ?: "Exception")),
                message = error.message ?: "field repair probe failed",
                observedAt = observedAt,
            )
        }
    }
}

class CompositeRepairProbe(
    probes: Iterable<RepairProbe>,
) {
    private val probes = probes.sortedBy { it.id.value }.also {
        require(it.isNotEmpty()) { "Composite repair probe requires at least one probe" }
        require(it.map { probe -> probe.id }.distinct().size == it.size) {
            "Repair probe ids must be unique"
        }
    }

    suspend fun runFor(targets: Set<HealthNodeId> = emptySet()): List<RepairProbeResult> {
        val selected = if (targets.isEmpty()) {
            probes
        } else {
            probes.filter { probe -> probe.targetNodes.any(targets::contains) }
        }
        return selected.map { it.run() }
    }
}
