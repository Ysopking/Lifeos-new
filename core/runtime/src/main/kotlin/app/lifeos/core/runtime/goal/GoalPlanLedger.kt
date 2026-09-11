package app.lifeos.core.runtime.goal

import app.lifeos.core.model.PhotonId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface GoalPlanDefinitionWriteResult {
    val definition: GoalPlanDefinition

    data class Stored(override val definition: GoalPlanDefinition) : GoalPlanDefinitionWriteResult
    data class Duplicate(override val definition: GoalPlanDefinition) : GoalPlanDefinitionWriteResult
}

sealed interface GoalPlanTransitionWriteResult {
    val transition: GoalPlanTransition

    data class Stored(override val transition: GoalPlanTransition) : GoalPlanTransitionWriteResult
    data class Duplicate(override val transition: GoalPlanTransition) : GoalPlanTransitionWriteResult
}

data class GoalPlanRepositoryLoadReport(
    val definitions: List<GoalPlanDefinition>,
    val transitions: List<GoalPlanTransition>,
    val unreadableEntries: List<String>,
) {
    init {
        require(definitions.map { it.id }.distinct().size == definitions.size) {
            "Goal plan load report contains duplicate definitions"
        }
        require(transitions.map { it.id }.distinct().size == transitions.size) {
            "Goal plan load report contains duplicate transitions"
        }
        require(unreadableEntries == unreadableEntries.distinct().sorted())
    }

    val isCorrupted: Boolean get() = unreadableEntries.isNotEmpty()
}

interface GoalPlanRepository {
    suspend fun saveDefinition(definition: GoalPlanDefinition): GoalPlanDefinitionWriteResult
    suspend fun loadDefinition(id: GoalPlanId): GoalPlanDefinition?
    suspend fun saveTransition(transition: GoalPlanTransition): GoalPlanTransitionWriteResult
    suspend fun loadTransitions(planId: GoalPlanId): List<GoalPlanTransition>
    suspend fun loadReport(): GoalPlanRepositoryLoadReport
}

data class GoalPlanRehydrateReport(
    val restoredPlans: Int,
    val restoredTransitions: Int,
    val states: Map<GoalPlanId, GoalPlanRuntimeState>,
)

/** Persistence-before-RAM owner of all durable long-horizon goal plans. */
class DurableGoalPlanLedger(
    private val repository: GoalPlanRepository,
    private val reducer: GoalPlanReducer = GoalPlanReducer(),
) {
    private val mutex = Mutex()
    private val mutableStates = MutableStateFlow<Map<GoalPlanId, GoalPlanRuntimeState>>(emptyMap())
    val states: StateFlow<Map<GoalPlanId, GoalPlanRuntimeState>> = mutableStates.asStateFlow()

    suspend fun create(definition: GoalPlanDefinition): GoalPlanRuntimeState = mutex.withLock {
        val persisted = repository.saveDefinition(definition).definition
        require(persisted == definition) { "Persisted goal plan differs from requested definition" }
        val existing = mutableStates.value[definition.id]
        if (existing != null) {
            require(existing.definition == definition) { "Goal plan identity collision in runtime state" }
            return@withLock existing
        }
        // A duplicate definition may already have durable progress after process death.
        val restored = reducer.replay(persisted, repository.loadTransitions(persisted.id))
        mutableStates.value = mutableStates.value + (definition.id to restored)
        restored
    }

    suspend fun append(transition: GoalPlanTransition): GoalTransitionApplyResult = mutex.withLock {
        val current = mutableStates.value[transition.planId]
            ?: error("Goal plan must be created or rehydrated before appending transitions")
        // A prior write can have reached disk even when its acknowledgement was lost.
        // Validate against durable lineage before writing, so a stale/illegal event
        // cannot poison the append-only vault or fork a successfully committed event.
        val durable = reducer.replay(
            current.definition,
            repository.loadTransitions(transition.planId),
        )
        val applied = reducer.apply(durable, transition)
        val persisted = repository.saveTransition(transition).transition
        require(persisted == transition) { "Persisted goal transition differs from requested event" }
        mutableStates.value = mutableStates.value + (transition.planId to applied.state)
        applied
    }

    suspend fun rehydrate(): GoalPlanRehydrateReport = mutex.withLock {
        val report = repository.loadReport()
        require(!report.isCorrupted) {
            "Goal plan history is corrupted: ${report.unreadableEntries.joinToString(",")}" 
        }
        val definitionsById = report.definitions.associateBy { it.id }
        require(report.transitions.all { it.planId in definitionsById }) {
            "Goal transition history references a missing plan definition"
        }
        val grouped = report.transitions.groupBy { it.planId }
        val restored = definitionsById
            .toSortedMap(compareBy { it.value })
            .mapValues { (id, definition) ->
                reducer.replay(definition, grouped[id].orEmpty())
            }
        mutableStates.value = restored
        GoalPlanRehydrateReport(
            restoredPlans = restored.size,
            restoredTransitions = report.transitions.size,
            states = restored,
        )
    }

    fun state(planId: GoalPlanId): GoalPlanRuntimeState? = mutableStates.value[planId]
}

object GoalPlanDefinitionCodec {
    const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private const val MAGIC = 0x47504437 // GPD7
    private const val VERSION = 1
    private const val MAX_STEPS = 4_096
    private const val MAX_DEPENDENCIES = 4_096

    fun encode(definition: GoalPlanDefinition): ByteArray = ByteArrayOutputStream().let { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeBoundedString(definition.id.value)
            out.writeBoundedString(definition.sourceGoalPhotonId.value)
            out.writeLong(definition.sourceGoalPhotonRevision)
            out.writeLong(definition.planRevision)
            out.writeBoundedString(definition.createdAt.toString())
            out.writeInt(definition.steps.size)
            definition.steps.sortedBy { it.id.value }.forEach { step ->
                out.writeBoundedString(step.id.value)
                out.writeBoundedString(step.key)
                out.writeBoundedString(step.objective)
                out.writeNullableInstant(step.deadline)
                out.writeInt(step.priority)
                out.writeInt(step.dependencyIds.size)
                step.dependencyIds.sortedBy { it.value }.forEach { dependency ->
                    out.writeBoundedString(dependency.value)
                }
            }
        }
        bytes.toByteArray().also(::requirePayloadSize)
    }

    fun decode(payload: ByteArray): GoalPlanDefinition {
        requirePayloadSize(payload)
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Unsupported goal plan definition magic" }
        require(input.readInt() == VERSION) { "Unsupported goal plan definition version" }
        val id = GoalPlanId(input.readBoundedString())
        val sourceId = PhotonId(input.readBoundedString())
        val sourceRevision = input.readLong()
        val planRevision = input.readLong()
        val createdAt = Instant.parse(input.readBoundedString())
        val stepCount = input.readInt()
        require(stepCount in 1..MAX_STEPS) { "Invalid goal plan step count" }
        val steps = buildList(stepCount) {
            repeat(stepCount) {
                val stepId = GoalStepId(input.readBoundedString())
                val key = input.readBoundedString()
                val objective = input.readBoundedString()
                val deadline = input.readNullableInstant()
                val priority = input.readInt()
                val dependencyCount = input.readInt()
                require(dependencyCount in 0..MAX_DEPENDENCIES) {
                    "Invalid goal plan dependency count"
                }
                val dependencies = buildSet {
                    repeat(dependencyCount) { add(GoalStepId(input.readBoundedString())) }
                }
                require(dependencies.size == dependencyCount) {
                    "Duplicate goal plan dependency in payload"
                }
                add(
                    GoalStepDefinition(
                        id = stepId,
                        key = key,
                        objective = objective,
                        dependencyIds = dependencies,
                        deadline = deadline,
                        priority = priority,
                    )
                )
            }
        }.sortedBy { it.id.value }
        require(input.available() == 0) { "Trailing goal plan definition bytes" }
        return GoalPlanDefinition(
            id = id,
            sourceGoalPhotonId = sourceId,
            sourceGoalPhotonRevision = sourceRevision,
            planRevision = planRevision,
            steps = steps,
            createdAt = createdAt,
        )
    }

    private fun requirePayloadSize(payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid goal plan definition payload size"
        }
    }
}

object GoalPlanTransitionCodec {
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    private const val MAGIC = 0x47505437 // GPT7
    private const val VERSION = 1

    fun encode(transition: GoalPlanTransition): ByteArray = ByteArrayOutputStream().let { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeBoundedString(transition.id.value)
            out.writeBoundedString(transition.planId.value)
            out.writeNullableString(transition.predecessorId?.value)
            out.writeBoundedString(transition.stepId.value)
            out.writeBoundedString(transition.fromState.name)
            out.writeBoundedString(transition.toState.name)
            out.writeBoundedString(transition.reason)
            out.writeBoundedString(transition.sourceFingerprint)
            out.writeNullableString(transition.decisionFingerprint)
            out.writeNullableString(transition.actionId)
            out.writeNullableString(transition.actionIdempotencyKey)
            out.writeNullableString(transition.outcomePhotonId?.value)
            out.writeBoundedString(transition.createdAt.toString())
        }
        bytes.toByteArray().also(::requirePayloadSize)
    }

    fun decode(payload: ByteArray): GoalPlanTransition {
        requirePayloadSize(payload)
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Unsupported goal plan transition magic" }
        require(input.readInt() == VERSION) { "Unsupported goal plan transition version" }
        val transition = GoalPlanTransition(
            id = GoalTransitionId(input.readBoundedString()),
            planId = GoalPlanId(input.readBoundedString()),
            predecessorId = input.readNullableString()?.let(::GoalTransitionId),
            stepId = GoalStepId(input.readBoundedString()),
            fromState = GoalStepState.valueOf(input.readBoundedString()),
            toState = GoalStepState.valueOf(input.readBoundedString()),
            reason = input.readBoundedString(),
            sourceFingerprint = input.readBoundedString(),
            decisionFingerprint = input.readNullableString(),
            actionId = input.readNullableString(),
            actionIdempotencyKey = input.readNullableString(),
            outcomePhotonId = input.readNullableString()?.let(::PhotonId),
            createdAt = Instant.parse(input.readBoundedString()),
        )
        require(input.available() == 0) { "Trailing goal plan transition bytes" }
        return transition
    }

    private fun requirePayloadSize(payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid goal plan transition payload size"
        }
    }
}

private const val GOAL_PLAN_MAX_STRING_BYTES = 64 * 1024

private fun DataOutputStream.writeBoundedString(value: String) {
    val encoded = value.toByteArray(StandardCharsets.UTF_8)
    require(encoded.size <= GOAL_PLAN_MAX_STRING_BYTES) { "Goal plan string too large" }
    writeInt(encoded.size)
    write(encoded)
}

private fun DataInputStream.readBoundedString(): String {
    val length = readInt()
    require(length in 0..GOAL_PLAN_MAX_STRING_BYTES) { "Invalid goal plan string length" }
    val bytes = ByteArray(length)
    readFully(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeBoundedString(value)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readBoundedString() else null

private fun DataOutputStream.writeNullableInstant(value: Instant?) {
    writeNullableString(value?.toString())
}

private fun DataInputStream.readNullableInstant(): Instant? =
    readNullableString()?.let(Instant::parse)
