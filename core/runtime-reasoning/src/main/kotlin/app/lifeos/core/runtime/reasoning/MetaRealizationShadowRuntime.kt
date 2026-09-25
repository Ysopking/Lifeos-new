package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.self.SelfStateProjectionResult
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    init {
        require(historyCapacity in 2..512)
    }

    private val mutex = Mutex()
    private val history = ArrayDeque<MetaRealizationShadowSnapshot>()
    private val mutableStatus =
        MutableStateFlow<MetaRealizationShadowStatus>(MetaRealizationShadowStatus.Idle)

    val status: StateFlow<MetaRealizationShadowStatus> = mutableStatus.asStateFlow()

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
}

object MetaRealizationShadowRuntimeRegistry {
    private val current = AtomicReference<MetaRealizationShadowRuntime?>(null)

    fun install(runtime: MetaRealizationShadowRuntime) {
        current.set(runtime)
    }

    fun currentOrNull(): MetaRealizationShadowRuntime? = current.get()

    fun requireCurrent(): MetaRealizationShadowRuntime =
        requireNotNull(current.get()) {
            "Meta-realization shadow runtime is not installed"
        }

    fun clearForTestOnly() {
        current.set(null)
    }
}
