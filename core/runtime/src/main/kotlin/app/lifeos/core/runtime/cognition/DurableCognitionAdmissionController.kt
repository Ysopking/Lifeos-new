package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.task.IndexedTaskSnapshotRepository
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import kotlinx.coroutines.sync.withLock

/**
 * Bounds the durable cognition backlog at the TaskStore boundary.
 *
 * Admission is serialized across every controller attached to the same [DurableTaskEngine], so
 * independent live-delta and feedback producers cannot all observe the same free slot. Existing
 * idempotent work is always allowed through even when the backlog is full. New cognition work may
 * additionally be rejected by the shared V16 World Formula broker when current device capacity is
 * better spent on higher-value work. A task-ledger integrity failure fails closed.
 */
class DurableCognitionAdmissionController(
    private val tasks: IndexedTaskSnapshotRepository,
    private val taskEngine: DurableTaskEngine,
    private val maxActiveTasks: Int = DEFAULT_MAX_ACTIVE_TASKS,
    private val sharedBudgets: SharedResourceBudgetGate? = SharedResourceBudgetRuntimeRegistry.current(),
) {
    private val mutex = taskEngine.cognitionAdmissionMutex

    init {
        require(maxActiveTasks > 0) { "Durable cognition capacity must be positive" }
    }

    suspend fun submit(draft: TaskDraft): LifeTask? = mutex.withLock {
        require(draft.type.isCognitionTask()) { "Admission controller accepts cognition tasks only" }

        val existing = tasks.findByIdempotencyKey(draft.idempotencyKey)
        if (existing != null) {
            check(existing.type == draft.type) {
                "Idempotency key collision across task types: ${draft.idempotencyKey}"
            }
            return@withLock taskEngine.submit(draft)
        }

        val active = tasks.activeCount(COGNITION_TYPES)
        if (active >= maxActiveTasks) return@withLock null

        sharedBudgets?.let { broker ->
            val priority = (draft.priority.weight.toDouble() / 100.0).coerceIn(0.0, 1.0)
            val demand = ResourceBudgetDemand(
                domain = ResourceBudgetDomain.COGNITION,
                requested = COGNITION_ADMISSION_REQUEST,
                goalRelevance = priority,
                priority = priority,
                expectedUtility = if (draft.priority.weight >= 75) 0.85 else 0.60,
                confidence = 1.0,
            )
            when (val decision = broker.allocate(COGNITION_HARD_QUOTA, listOf(demand))) {
                is SharedResourceBudgetDecision.Blocked -> return@withLock null
                is SharedResourceBudgetDecision.Ready -> {
                    val allocation = decision.allocation.allocation(ResourceBudgetDomain.COGNITION)
                        ?: return@withLock null
                    if (!COGNITION_ADMISSION_REQUEST.isWithin(allocation.allocated)) {
                        return@withLock null
                    }
                }
            }
        }

        taskEngine.submit(draft)
    }

    suspend fun submitBatch(drafts: List<TaskDraft>): List<LifeTask?> = mutex.withLock {
        if (drafts.isEmpty()) return@withLock emptyList()
        require(drafts.all { it.type.isCognitionTask() }) {
            "Admission controller accepts cognition tasks only"
        }
        require(drafts.map { it.idempotencyKey }.distinct().size == drafts.size) {
            "Cognition admission batch contains duplicate idempotency keys"
        }

        val existing = linkedMapOf<Int, LifeTask>()
        val newIndices = mutableListOf<Int>()
        drafts.forEachIndexed { index, draft ->
            val task = tasks.findByIdempotencyKey(draft.idempotencyKey)
            if (task == null) {
                newIndices += index
            } else {
                check(task.type == draft.type) {
                    "Idempotency key collision across task types: ${draft.idempotencyKey}"
                }
                existing[index] = task
            }
        }

        val active = tasks.activeCount(COGNITION_TYPES)
        val capacity = (maxActiveTasks - active).coerceAtLeast(0)
        val acceptedNew = newIndices.take(capacity)

        if (acceptedNew.isNotEmpty()) {
            sharedBudgets?.let { broker ->
                val request = scaleUsage(COGNITION_ADMISSION_REQUEST, acceptedNew.size.toLong())
                val maxPriority = acceptedNew.maxOf { drafts[it].priority.weight }
                val priority = (maxPriority.toDouble() / 100.0).coerceIn(0.0, 1.0)
                val demand = ResourceBudgetDemand(
                    domain = ResourceBudgetDomain.COGNITION,
                    requested = request,
                    goalRelevance = priority,
                    priority = priority,
                    expectedUtility = if (maxPriority >= 75) 0.85 else 0.60,
                    confidence = 1.0,
                )
                when (val decision = broker.allocate(scaleQuota(COGNITION_HARD_QUOTA, acceptedNew.size.toLong()), listOf(demand))) {
                    is SharedResourceBudgetDecision.Blocked ->
                        return@withLock drafts.indices.map { existing[it] }
                    is SharedResourceBudgetDecision.Ready -> {
                        val allocation = decision.allocation.allocation(ResourceBudgetDomain.COGNITION)
                            ?: return@withLock drafts.indices.map { existing[it] }
                        if (!request.isWithin(allocation.allocated)) {
                            return@withLock drafts.indices.map { existing[it] }
                        }
                    }
                }
            }
        }

        val admitted = acceptedNew.toSet()
        drafts.indices.map { index ->
            when {
                index in existing -> taskEngine.submit(drafts[index])
                index in admitted -> taskEngine.submit(drafts[index])
                else -> null
            }
        }
    }

    suspend fun availableCapacity(): Int = mutex.withLock {
        val active = tasks.activeCount(COGNITION_TYPES)
        (maxActiveTasks - active).coerceAtLeast(0)
    }

    private fun TaskType.isCognitionTask(): Boolean = this in COGNITION_TYPES

    private fun scaleUsage(value: ResourceBudgetUsage, count: Long): ResourceBudgetUsage =
        ResourceBudgetUsage(
            elapsedMillis = Math.multiplyExact(value.elapsedMillis, count),
            workUnits = Math.multiplyExact(value.workUnits, count),
            memoryBytes = Math.multiplyExact(value.memoryBytes, count),
            ioBytes = Math.multiplyExact(value.ioBytes, count),
            networkBytes = Math.multiplyExact(value.networkBytes, count),
            candidates = Math.multiplyExact(value.candidates, count),
        )

    private fun scaleQuota(value: ResourceBudgetQuota, count: Long): ResourceBudgetQuota =
        ResourceBudgetQuota(
            elapsedMillis = Math.multiplyExact(value.elapsedMillis, count),
            workUnits = Math.multiplyExact(value.workUnits, count),
            memoryBytes = Math.multiplyExact(value.memoryBytes, count),
            ioBytes = Math.multiplyExact(value.ioBytes, count),
            networkBytes = Math.multiplyExact(value.networkBytes, count),
            candidates = Math.multiplyExact(value.candidates, count),
        )

    private companion object {
        const val DEFAULT_MAX_ACTIVE_TASKS = 100
        val COGNITION_TYPES = setOf(TaskType.PROCESS_PHOTON, TaskType.REPROCESS_PHOTON)
        val COGNITION_HARD_QUOTA = ResourceBudgetQuota(
            elapsedMillis = 5_000,
            workUnits = 32,
            memoryBytes = 128L * 1024L * 1024L,
            ioBytes = 16L * 1024L * 1024L,
            networkBytes = 0,
            candidates = 8,
        )
        val COGNITION_ADMISSION_REQUEST = ResourceBudgetUsage(
            elapsedMillis = 500,
            workUnits = 1,
            memoryBytes = 8L * 1024L * 1024L,
            ioBytes = 512L * 1024L,
            networkBytes = 0,
            candidates = 1,
        )
    }
}
