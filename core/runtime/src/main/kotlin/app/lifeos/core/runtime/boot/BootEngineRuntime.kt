package app.lifeos.core.runtime.boot

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.ProductiveWorldCandidate
import app.lifeos.core.runtime.world.ProductiveWorldCommitResult
import app.lifeos.core.runtime.world.ProductiveWorldFormulaRequest
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.ProductiveWorldHeadCommitter
import app.lifeos.core.runtime.world.ProductiveWorldHeadRepository
import app.lifeos.core.runtime.world.PersonalContextBootBinding
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaCycleContext
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BootEngineFrozenInputs(
    val representationSnapshotId: String,
    val strategySnapshotId: String,
    val equationVersion: String,
    val resourceSnapshotId: String,
    val perceptionBinding: PersonalContextBootBinding? = null,
) {
    init {
        require(representationSnapshotId.isNotBlank())
        require(strategySnapshotId.isNotBlank())
        require(equationVersion.isNotBlank())
        require(resourceSnapshotId.isNotBlank())
    }

    fun fingerprint(): String =
        if (perceptionBinding == null) {
            StableFieldIds.fingerprint(
                "boot-engine-frozen-inputs/v1",
                representationSnapshotId,
                strategySnapshotId,
                equationVersion,
                resourceSnapshotId,
            )
        } else {
            StableFieldIds.fingerprint(
                "boot-engine-frozen-inputs/v2",
                representationSnapshotId,
                strategySnapshotId,
                equationVersion,
                resourceSnapshotId,
                perceptionBinding.fingerprint,
            )
        }
}

enum class BootEngineCycleState {
    PREPARED,
    WORLD_EVALUATED,
    COMMITTED,
    FAILED,
}

data class BootEngineCycle private constructor(
    val cycleId: CognitiveCycleId,
    val context: WorldFormulaCycleContext,
    val frozenInputsFingerprint: String,
    val state: BootEngineCycleState,
    val productiveRequestId: String? = null,
    val worldSnapshotId: String? = null,
    val productiveHeadRevision: Long? = null,
    val failure: String? = null,
    val fingerprint: String,
) {
    init {
        require(frozenInputsFingerprint.isNotBlank())
        require(context.cycleId == cycleId)
        require(fingerprint == expectedFingerprint()) {
            "BootEngine cycle fingerprint does not match content"
        }
        when (state) {
            BootEngineCycleState.PREPARED -> {
                require(productiveRequestId == null)
                require(worldSnapshotId == null)
                require(productiveHeadRevision == null)
                require(failure == null)
            }
            BootEngineCycleState.WORLD_EVALUATED -> {
                require(!productiveRequestId.isNullOrBlank())
                require(!worldSnapshotId.isNullOrBlank())
                require(productiveHeadRevision == null)
                require(failure == null)
            }
            BootEngineCycleState.COMMITTED -> {
                require(!productiveRequestId.isNullOrBlank())
                require(!worldSnapshotId.isNullOrBlank())
                require(productiveHeadRevision != null && productiveHeadRevision > 0L)
                require(failure == null)
            }
            BootEngineCycleState.FAILED -> {
                require(!failure.isNullOrBlank())
            }
        }
    }

    val terminal: Boolean
        get() = state == BootEngineCycleState.COMMITTED || state == BootEngineCycleState.FAILED

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "boot-engine-cycle/v1",
        cycleId.value,
        context.fingerprint(),
        frozenInputsFingerprint,
        state.name,
        productiveRequestId.orEmpty(),
        worldSnapshotId.orEmpty(),
        productiveHeadRevision?.toString().orEmpty(),
        failure.orEmpty(),
    )

    fun evaluated(
        requestId: String,
        snapshotId: String,
    ): BootEngineCycle {
        require(state == BootEngineCycleState.PREPARED)
        require(requestId.isNotBlank() && snapshotId.isNotBlank())
        return create(
            cycleId = cycleId,
            context = context,
            frozenInputsFingerprint = frozenInputsFingerprint,
            state = BootEngineCycleState.WORLD_EVALUATED,
            productiveRequestId = requestId,
            worldSnapshotId = snapshotId,
        )
    }

    fun committed(headRevision: Long): BootEngineCycle {
        require(state == BootEngineCycleState.WORLD_EVALUATED)
        require(headRevision > 0L)
        return create(
            cycleId = cycleId,
            context = context,
            frozenInputsFingerprint = frozenInputsFingerprint,
            state = BootEngineCycleState.COMMITTED,
            productiveRequestId = productiveRequestId,
            worldSnapshotId = worldSnapshotId,
            productiveHeadRevision = headRevision,
        )
    }

    fun failed(reason: String): BootEngineCycle {
        require(!terminal)
        require(reason.isNotBlank())
        return create(
            cycleId = cycleId,
            context = context,
            frozenInputsFingerprint = frozenInputsFingerprint,
            state = BootEngineCycleState.FAILED,
            productiveRequestId = productiveRequestId,
            worldSnapshotId = worldSnapshotId,
            failure = reason,
        )
    }

    companion object {
        fun prepared(
            cycleId: CognitiveCycleId,
            context: WorldFormulaCycleContext,
            frozenInputs: BootEngineFrozenInputs,
        ): BootEngineCycle {
            require(context.cycleId == cycleId)
            require(context.representationSnapshotId == frozenInputs.representationSnapshotId)
            require(context.strategySnapshotId == frozenInputs.strategySnapshotId)
            require(context.equationVersion == frozenInputs.equationVersion)
            require(context.resourceSnapshotId == frozenInputs.resourceSnapshotId)
            require(context.perceptionBinding == frozenInputs.perceptionBinding) {
                "BootEngine cycle perception binding differs from frozen inputs"
            }
            return create(
                cycleId = cycleId,
                context = context,
                frozenInputsFingerprint = frozenInputs.fingerprint(),
                state = BootEngineCycleState.PREPARED,
            )
        }

        fun restore(
            cycleId: CognitiveCycleId,
            context: WorldFormulaCycleContext,
            frozenInputsFingerprint: String,
            state: BootEngineCycleState,
            productiveRequestId: String?,
            worldSnapshotId: String?,
            productiveHeadRevision: Long?,
            failure: String?,
            fingerprint: String,
        ): BootEngineCycle = BootEngineCycle(
            cycleId = cycleId,
            context = context,
            frozenInputsFingerprint = frozenInputsFingerprint,
            state = state,
            productiveRequestId = productiveRequestId,
            worldSnapshotId = worldSnapshotId,
            productiveHeadRevision = productiveHeadRevision,
            failure = failure,
            fingerprint = fingerprint,
        )

        private fun create(
            cycleId: CognitiveCycleId,
            context: WorldFormulaCycleContext,
            frozenInputsFingerprint: String,
            state: BootEngineCycleState,
            productiveRequestId: String? = null,
            worldSnapshotId: String? = null,
            productiveHeadRevision: Long? = null,
            failure: String? = null,
        ): BootEngineCycle {
            val fingerprint = StableFieldIds.fingerprint(
                "boot-engine-cycle/v1",
                cycleId.value,
                context.fingerprint(),
                frozenInputsFingerprint,
                state.name,
                productiveRequestId.orEmpty(),
                worldSnapshotId.orEmpty(),
                productiveHeadRevision?.toString().orEmpty(),
                failure.orEmpty(),
            )
            return BootEngineCycle(
                cycleId = cycleId,
                context = context,
                frozenInputsFingerprint = frozenInputsFingerprint,
                state = state,
                productiveRequestId = productiveRequestId,
                worldSnapshotId = worldSnapshotId,
                productiveHeadRevision = productiveHeadRevision,
                failure = failure,
                fingerprint = fingerprint,
            )
        }
    }
}

data class BootEngineCycleLoadReport(
    val activeCycle: BootEngineCycle?,
    val latestCommitted: BootEngineCycle?,
    val corrupted: Boolean,
    val message: String?,
) {
    init {
        require(message == null || message.isNotBlank())
        require(!corrupted || message != null) {
            "Corrupted BootEngine cycle report requires a message"
        }
        require(activeCycle == null || !activeCycle.terminal) {
            "Active BootEngine cycle report cannot expose a terminal cycle"
        }
        require(latestCommitted == null || latestCommitted.state == BootEngineCycleState.COMMITTED) {
            "Latest committed BootEngine cycle must be COMMITTED"
        }
    }
}

interface BootEngineCycleRepository {
    suspend fun create(cycle: BootEngineCycle): Boolean
    suspend fun load(cycleId: CognitiveCycleId): BootEngineCycle?
    suspend fun loadActive(): BootEngineCycle?
    suspend fun loadLatestCommitted(): BootEngineCycle? = null
    suspend fun compareAndSet(
        expectedFingerprint: String,
        next: BootEngineCycle,
    ): Boolean

    suspend fun loadReport(): BootEngineCycleLoadReport =
        BootEngineCycleLoadReport(
            activeCycle = loadActive(),
            latestCommitted = loadLatestCommitted(),
            corrupted = false,
            message = null,
        )
}

sealed interface BootEngineWorldEvaluation {
    data class Ready(
        val cycle: BootEngineCycle,
        val candidate: ProductiveWorldCandidate,
    ) : BootEngineWorldEvaluation

    data class Failed(
        val cycle: BootEngineCycle,
        val reason: String,
    ) : BootEngineWorldEvaluation
}

sealed interface BootEngineCommitResult {
    data class Committed(
        val cycle: BootEngineCycle,
        val worldHead: ProductiveWorldHead,
    ) : BootEngineCommitResult

    data object ConcurrentWorldHeadChanged : BootEngineCommitResult
    data object ConcurrentCycleChanged : BootEngineCommitResult

    data class Blocked(val reason: String) : BootEngineCommitResult {
        init {
            require(reason.isNotBlank())
        }
    }
}

sealed interface BootEngineRecoveryResult {
    data object NoActiveCycle : BootEngineRecoveryResult
    data class ResumePrepared(val cycle: BootEngineCycle) : BootEngineRecoveryResult
    data class ResumeCommit(val cycle: BootEngineCycle) : BootEngineRecoveryResult
    data class RecoveredCommitted(val cycle: BootEngineCycle) : BootEngineRecoveryResult
}

/**
 * B162 unified cognitive lifecycle owner.
 *
 * BootCoordinator remains the one-shot boot transaction. BootEngineRuntime advances explicit,
 * durable cognitive cycles; it starts no second polling loop and cannot change frozen physics inside
 * an active cycle.
 */
class BootEngineRuntime(
    private val cycles: BootEngineCycleRepository,
    private val worldHeads: ProductiveWorldHeadRepository,
    private val worldCoordinator: WorldFormulaCoordinator,
    private val worldCommitter: ProductiveWorldHeadCommitter,
    private val newCycleId: () -> CognitiveCycleId,
) {
    private val mutex = Mutex()

    suspend fun activeCycle(): BootEngineCycle? = mutex.withLock {
        val report = cycles.loadReport()
        require(!report.corrupted) {
            "BootEngine cycle recovery required: ${report.message}"
        }
        report.activeCycle
    }

    suspend fun startCycle(
        frozenInputs: BootEngineFrozenInputs,
    ): BootEngineCycle = mutex.withLock {
        val active = cycles.loadActive()
        require(active == null) {
            "BootEngine cannot start a new cycle while ${active?.cycleId} is active"
        }
        val currentWorldHead = worldHeads.load()
        val cycleId = newCycleId()
        val context = WorldFormulaCycleContext(
            cycleId = cycleId,
            previousWorldSnapshotId = currentWorldHead?.activeSnapshot?.snapshotId,
            representationSnapshotId = frozenInputs.representationSnapshotId,
            strategySnapshotId = frozenInputs.strategySnapshotId,
            equationVersion = frozenInputs.equationVersion,
            resourceSnapshotId = frozenInputs.resourceSnapshotId,
            perceptionBinding = frozenInputs.perceptionBinding,
        )
        val cycle = BootEngineCycle.prepared(cycleId, context, frozenInputs)
        check(cycles.create(cycle)) {
            "BootEngine cycle already exists"
        }
        cycle
    }

    suspend fun evaluate(
        cycleId: CognitiveCycleId,
        request: ProductiveWorldFormulaRequest,
    ): BootEngineWorldEvaluation = mutex.withLock {
        val cycle = requireNotNull(cycles.load(cycleId)) {
            "Unknown BootEngine cycle $cycleId"
        }
        require(cycle.state == BootEngineCycleState.PREPARED) {
            "BootEngine world evaluation requires PREPARED cycle"
        }
        require(request.cycle == cycle.context) {
            "BootEngine request changed frozen cycle context"
        }

        val execution = worldCoordinator.evaluate(request.request)
        if (
            execution.state != WorldFormulaExecutionState.COMPLETED ||
            !execution.persisted ||
            execution.snapshot == null
        ) {
            val failed = cycle.failed(
                "world-formula:${execution.state.name}:${execution.message}"
            )
            check(cycles.compareAndSet(cycle.fingerprint, failed)) {
                "BootEngine cycle changed while recording WorldFormula failure"
            }
            return@withLock BootEngineWorldEvaluation.Failed(
                cycle = failed,
                reason = requireNotNull(failed.failure),
            )
        }

        val candidate = ProductiveWorldCandidate.from(request, execution)
        val evaluated = cycle.evaluated(
            requestId = request.id,
            snapshotId = candidate.snapshot.id,
        )
        check(cycles.compareAndSet(cycle.fingerprint, evaluated)) {
            "BootEngine cycle changed while recording WorldFormula evaluation"
        }
        BootEngineWorldEvaluation.Ready(evaluated, candidate)
    }

    suspend fun commit(
        evaluation: BootEngineWorldEvaluation.Ready,
    ): BootEngineCommitResult = mutex.withLock {
        val durableCycle = requireNotNull(cycles.load(evaluation.cycle.cycleId)) {
            "BootEngine cycle disappeared before commit"
        }
        if (durableCycle.fingerprint != evaluation.cycle.fingerprint) {
            return@withLock BootEngineCommitResult.ConcurrentCycleChanged
        }
        require(durableCycle.state == BootEngineCycleState.WORLD_EVALUATED)
        require(evaluation.candidate.request.cycle == durableCycle.context)
        require(evaluation.candidate.request.id == durableCycle.productiveRequestId)
        require(evaluation.candidate.snapshot.id == durableCycle.worldSnapshotId)

        val liveHead = worldHeads.load()
        val expectedPrevious = durableCycle.context.previousWorldSnapshotId
        if (liveHead?.activeSnapshot?.snapshotId != expectedPrevious) {
            if (!(liveHead == null && expectedPrevious == null)) {
                return@withLock BootEngineCommitResult.ConcurrentWorldHeadChanged
            }
        }

        when (
            val committed = worldCommitter.commit(
                candidate = evaluation.candidate,
                expectedHead = liveHead,
            )
        ) {
            is ProductiveWorldCommitResult.Committed -> {
                val completed = durableCycle.committed(committed.currentHead.revision)
                if (!cycles.compareAndSet(durableCycle.fingerprint, completed)) {
                    return@withLock BootEngineCommitResult.ConcurrentCycleChanged
                }
                BootEngineCommitResult.Committed(
                    cycle = completed,
                    worldHead = committed.currentHead,
                )
            }
            ProductiveWorldCommitResult.ConcurrentHeadChanged ->
                BootEngineCommitResult.ConcurrentWorldHeadChanged
            is ProductiveWorldCommitResult.Blocked ->
                BootEngineCommitResult.Blocked(committed.reason)
        }
    }

    suspend fun failEvaluation(
        evaluation: BootEngineWorldEvaluation.Ready,
        reason: String,
    ): BootEngineCycle = mutex.withLock {
        require(reason.isNotBlank())
        val durableCycle = requireNotNull(cycles.load(evaluation.cycle.cycleId)) {
            "BootEngine cycle disappeared before fail-closed terminalization"
        }
        require(durableCycle.fingerprint == evaluation.cycle.fingerprint) {
            "BootEngine cycle changed before fail-closed terminalization"
        }
        require(durableCycle.state == BootEngineCycleState.WORLD_EVALUATED) {
            "BootEngine can fail-close only a WORLD_EVALUATED cycle"
        }
        val failed = durableCycle.failed(reason)
        check(cycles.compareAndSet(durableCycle.fingerprint, failed)) {
            "BootEngine cycle changed while recording fail-closed terminalization"
        }
        failed
    }

    suspend fun recover(): BootEngineRecoveryResult = mutex.withLock {
        val worldReport = worldHeads.loadReport()
        require(!worldReport.corrupted) {
            "Productive world head recovery required: ${worldReport.message}"
        }
        val cycleReport = cycles.loadReport()
        require(!cycleReport.corrupted) {
            "BootEngine cycle recovery required: ${cycleReport.message}"
        }
        val cycle = cycleReport.activeCycle
            ?: return@withLock BootEngineRecoveryResult.NoActiveCycle

        when (cycle.state) {
            BootEngineCycleState.PREPARED ->
                BootEngineRecoveryResult.ResumePrepared(cycle)

            BootEngineCycleState.WORLD_EVALUATED -> {
                val head = worldReport.head
                val alreadyCommitted =
                    head?.cycleId == cycle.cycleId &&
                        head.activeSnapshot.snapshotId == cycle.worldSnapshotId &&
                        head.cycleContextFingerprint == cycle.context.fingerprint()
                if (alreadyCommitted) {
                    val completed = cycle.committed(requireNotNull(head).revision)
                    check(cycles.compareAndSet(cycle.fingerprint, completed)) {
                        "BootEngine recovery could not finalize committed cycle"
                    }
                    BootEngineRecoveryResult.RecoveredCommitted(completed)
                } else {
                    BootEngineRecoveryResult.ResumeCommit(cycle)
                }
            }

            BootEngineCycleState.COMMITTED,
            BootEngineCycleState.FAILED -> error(
                "Terminal BootEngine cycle must not be returned as active"
            )
        }
    }
}
