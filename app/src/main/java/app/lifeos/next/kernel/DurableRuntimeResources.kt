package app.lifeos.next.kernel

import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.health.CircuitBreaker
import app.lifeos.core.runtime.health.HealthGate
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.QuarantineRegistry
import app.lifeos.core.runtime.health.RuntimeHealthMonitor
import app.lifeos.core.runtime.recovery.LeaseRecoveryService

internal data class DurableRuntimeResources(
    val runtime: LifeOsRuntime,
    val taskRepository: TaskRepository,
    val checkpointRepository: CheckpointRepository,
    val leaseRecovery: LeaseRecoveryService,
    val healthGraph: HealthGraph,
    val circuitBreaker: CircuitBreaker,
    val quarantineRegistry: QuarantineRegistry,
    val healthGate: HealthGate,
    val runtimeHealthMonitor: RuntimeHealthMonitor,
)
