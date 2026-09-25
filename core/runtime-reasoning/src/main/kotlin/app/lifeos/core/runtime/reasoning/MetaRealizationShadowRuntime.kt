package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.self.SelfStateProjectionResult
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface MetaRealizationShadowStatus {
    data object Idle : MetaRealizationShadowStatus

    data class Ready(
        val snapshot: MetaRealizationShadowSnapshot,
    ) : MetaRealizationShadowStatus

    data class Failed(
        val sourceSelfStateFingerprint: String,
        val message: String,
    ) : MetaRealizationShadowStatus {
        init {
            require(sourceSelfStateFingerprint.isNotBlank())
            require(message.isNotBlank())
        }
    }
}

data class MetaRealizationShadowSnapshot(
    val sourceSelfStateFingerprint: String,
    val sourceIssueFingerprint: String,
    val realization: CanonicalRealizationState,
    val cycle: MetaRealizationCycle,
    val fingerprint: String,
) {
    init {
        require(sourceSelfStateFingerprint.isNotBlank())
        require(sourceIssueFingerprint.isNotBlank())
        require(cycle.sourceRevisionId == realization.revisionId)
        require(cycle.state == MetaRealizationCycleState.STATE_FROZEN)
        require(
            fingerprint == StableFieldIds.fingerprint(
                "meta-realization-shadow-snapshot/v1",
                sourceSelfStateFingerprint,
                sourceIssueFingerprint,
                realization.representationFingerprint,
                cycle.fingerprint,
            )
        )
    }

    val truthAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false
}

/**
 * B528 live but non-authoritative wiring.
 *
 * Each emitted SelfObservation is copied into an immutable canonical realization revision and a
 * fresh STATE_FROZEN M9 cycle. The runtime keeps a bounded in-memory audit window only. It does
 * not execute information actions, mutate ProductiveWorld, promote WorldEquation candidates, or
 * claim causal truth. Shadow-mapping failures are contained in status and cannot fail productive
 * self-observation.
 */
class MetaRealizationShadowRuntime(
    val profile: RealizationTransferProfile = SelfObservationRealizationAdapter.profile(),
    private val historyCapacity: Int = 32,
    submissionCapacity: Int = 16,
    processingScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    init {
        require(historyCapacity in 2..512)
        require(submissionCapacity in 1..256)
    }

    private val mutex = Mutex()
    private val projectionHistoryAnalyzer =
        MetaRealizationProjectionHistoryAnalyzer()
    private val shadowEvaluator = MetaRealizationShadowEvaluator()
    private val submissions = Channel<SelfStateProjectionResult>(capacity = submissionCapacity)
    private val rejectedSubmissions = AtomicLong(0L)
    private val history = ArrayDeque<MetaRealizationShadowSnapshot>()
    private val mutableStatus =
        MutableStateFlow<MetaRealizationShadowStatus>(MetaRealizationShadowStatus.Idle)

    val status: StateFlow<MetaRealizationShadowStatus> = mutableStatus.asStateFlow()

    private val worker = processingScope.launch {
        for (result in submissions) {
            observe(result)
        }
    }

    /**
     * Non-blocking productive-path handoff. Queue saturation never blocks SelfObservation;
     * the shadow sample is rejected and counted instead.
     */
    fun submit(result: SelfStateProjectionResult): Boolean {
        val accepted = submissions.trySend(result).isSuccess
        if (!accepted) {
            rejectedSubmissions.incrementAndGet()
        }
        return accepted
    }

    fun rejectedSubmissionCount(): Long = rejectedSubmissions.get()

    suspend fun observe(
        result: SelfStateProjectionResult,
    ): MetaRealizationShadowStatus = mutex.withLock {
        try {
            val previous = history.peekLast()
            val revision = (previous?.realization?.revision ?: 0L) + 1L
            val sourceFingerprint = result.snapshot.stateFingerprint
            val issueFingerprint =
                SelfObservationRealizationAdapter.issueFingerprint(result.issues)
            val transitionFingerprint = previous?.let {
                StableFieldIds.fingerprint(
                    "meta-realization-self-transition/v1",
                    it.realization.revisionId,
                    it.sourceSelfStateFingerprint,
                    sourceFingerprint,
                    issueFingerprint,
                )
            }
            val realization = CanonicalRealizationState.create(
                revision = revision,
                asOf = result.snapshot.capturedAt,
                components = SelfObservationRealizationAdapter.components(result),
                predecessorRevisionId = previous?.realization?.revisionId,
                transitionFingerprint = transitionFingerprint,
            )
            val cycle = MetaRealizationCycle.start(
                source = realization,
                profile = profile,
            )
            val snapshot = MetaRealizationShadowSnapshot(
                sourceSelfStateFingerprint = sourceFingerprint,
                sourceIssueFingerprint = issueFingerprint,
                realization = realization,
                cycle = cycle,
                fingerprint = StableFieldIds.fingerprint(
                    "meta-realization-shadow-snapshot/v1",
                    sourceFingerprint,
                    issueFingerprint,
                    realization.representationFingerprint,
                    cycle.fingerprint,
                ),
            )
            history.addLast(snapshot)
            while (history.size > historyCapacity) {
                history.removeFirst()
            }
            MetaRealizationShadowStatus.Ready(snapshot).also {
                mutableStatus.value = it
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MetaRealizationShadowStatus.Failed(
                sourceSelfStateFingerprint = result.snapshot.stateFingerprint,
                message =
                    error.message ?: error::class.simpleName ?: "meta-realization-shadow-failed",
            ).also {
                mutableStatus.value = it
            }
        }
    }

    suspend fun history(): List<MetaRealizationShadowSnapshot> =
        mutex.withLock { history.toList() }

    /**
     * On-demand closure analysis. It snapshots the bounded shadow history under the mutex and
     * performs the CPU work after releasing the productive shadow state lock.
     */
    suspend fun projectionClosure(
        componentKind: RealizationComponentKind,
        projectionId: String = "meta-shadow:${componentKind.name.lowercase()}",
    ): ProjectionClosureResult? {
        val snapshotHistory = mutex.withLock { history.toList() }
        return projectionHistoryAnalyzer.analyze(
            history = snapshotHistory,
            componentKind = componentKind,
            projectionId = projectionId,
        )
    }

    /**
     * Explicit on-demand predictive evaluation for one retained shadow snapshot.
     * Evidence evaluation happens after the history lock is released.
     */
    suspend fun evaluateEvidence(
        snapshotFingerprint: String,
        evidence: MetaRealizationShadowEvidence,
    ): MetaRealizationShadowAnalysis {
        require(snapshotFingerprint.isNotBlank())
        val snapshot = mutex.withLock {
            history.singleOrNull { it.fingerprint == snapshotFingerprint }
        } ?: error("Unknown meta-realization shadow snapshot: $snapshotFingerprint")
        return shadowEvaluator.evaluate(snapshot, evidence)
    }

    fun close() {
        submissions.close()
        worker.cancel()
    }
}

object MetaRealizationShadowRuntimeRegistry {
    private val current = AtomicReference<MetaRealizationShadowRuntime?>(null)

    fun install(runtime: MetaRealizationShadowRuntime) {
        current.getAndSet(runtime)
            ?.takeIf { it !== runtime }
            ?.close()
    }

    fun currentOrNull(): MetaRealizationShadowRuntime? = current.get()

    fun requireCurrent(): MetaRealizationShadowRuntime =
        requireNotNull(current.get()) {
            "Meta-realization shadow runtime is not installed"
        }

    fun clearForTestOnly() {
        current.getAndSet(null)?.close()
    }
}
