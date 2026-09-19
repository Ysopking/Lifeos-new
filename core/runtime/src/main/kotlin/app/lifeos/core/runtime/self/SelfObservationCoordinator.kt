package app.lifeos.core.runtime.self

import app.lifeos.core.field.StableFieldIds
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SelfObservationTrigger {
    STARTUP,
    TIMER,
    HEALTH_TRANSITION,
    WORLD_HEAD_TRANSITION,
    EQUATION_HEAD_TRANSITION,
    RECOVERY_TRANSITION,
    TOOL_STATE_TRANSITION,
    EXPLICIT_UI_REFRESH,
}

enum class SelfObservationBand {
    STABLE,
    ACTIVE,
    DEGRADED,
    RECOVERING,
    CRITICAL,
}

data class SelfObservationCycle(
    val trigger: SelfObservationTrigger,
    val band: SelfObservationBand,
    val result: SelfStateProjectionResult,
    val materialFingerprint: String,
    val emitted: Boolean,
) {
    val snapshot: LifeOsSelfStateSnapshot
        get() = result.snapshot
}

fun interface SelfObservationCapture {
    suspend fun capture(): SelfStateProjectionResult
}

/**
 * Adaptive read-only self-observation loop authority.
 *
 * Refreshes are serialized, never persist a poll, and only publish materially distinct observations
 * (except an explicit UI refresh). The underlying capture remains free of mutation authority.
 */
class SelfObservationCoordinator(
    private val capture: SelfObservationCapture,
    private val policy: SelfObservationPolicy = SelfObservationPolicy(),
) {
    private val mutex = Mutex()
    private val mutableCycles = MutableStateFlow<SelfObservationCycle?>(null)

    fun observe(): StateFlow<SelfObservationCycle?> = mutableCycles.asStateFlow()

    suspend fun refresh(trigger: SelfObservationTrigger): SelfObservationCycle = mutex.withLock {
        val result = capture.capture()
        val materialFingerprint = materialFingerprint(result)
        val previous = mutableCycles.value
        val band = severityBand(result) ?: when {
            previous == null -> SelfObservationBand.ACTIVE
            previous.materialFingerprint != materialFingerprint -> SelfObservationBand.ACTIVE
            else -> SelfObservationBand.STABLE
        }
        val shouldEmit =
            previous == null ||
                previous.materialFingerprint != materialFingerprint ||
                previous.band != band ||
                trigger == SelfObservationTrigger.EXPLICIT_UI_REFRESH

        val cycle = SelfObservationCycle(
            trigger = trigger,
            band = band,
            result = result,
            materialFingerprint = materialFingerprint,
            emitted = shouldEmit,
        )
        if (shouldEmit) mutableCycles.value = cycle
        cycle
    }

    fun nextInterval(band: SelfObservationBand): Duration = policy.intervalFor(band)

    private fun severityBand(result: SelfStateProjectionResult): SelfObservationBand? {
        val snapshot = result.snapshot
        if (
            result.issues.any { it.kind == SelfObservationIssueKind.CORRUPT } ||
            positive(snapshot.health.unhealthy) ||
            positive(snapshot.health.quarantined) ||
            positive(snapshot.health.disabled)
        ) {
            return SelfObservationBand.CRITICAL
        }
        if (
            positive(snapshot.health.recovering) ||
            positive(snapshot.recovery.activeRepairs)
        ) {
            return SelfObservationBand.RECOVERING
        }
        if (
            positive(snapshot.health.degraded) ||
            result.issues.any { it.kind == SelfObservationIssueKind.FAILED } ||
            snapshot.runtime.unavailableSubsystems?.isNotEmpty() == true
        ) {
            return SelfObservationBand.DEGRADED
        }
        return null
    }

    private fun materialFingerprint(result: SelfStateProjectionResult): String {
        val snapshot = result.snapshot
        return StableFieldIds.fingerprint(
            "lifeos-self-observation-material/v1",
            snapshot.authorityFingerprint,
            canonical("runtime.operational", snapshot.runtime.operationalSubsystems),
            canonical("runtime.degraded", snapshot.runtime.degradedSubsystems),
            canonical("runtime.unavailable", snapshot.runtime.unavailableSubsystems),
            canonical("runtime.unbound", snapshot.runtime.unboundSubsystems),
            "resource.memory=" + ratioBucket(snapshot.resource.memoryHeadroom),
            "resource.storage=" + ratioBucket(snapshot.resource.storageHeadroom),
            "resource.thermal=" + ratioBucket(snapshot.resource.thermalHeadroom),
            "resource.energy=" + ratioBucket(snapshot.resource.energyAvailability),
            "resource.capability=" + ratioBucket(snapshot.resource.capabilityReadiness),
            "runtime.heap=" + ratioBucket(snapshot.runtime.telemetry?.heapHeadroom()),
            "runtime.threads=" + threadBucket(snapshot.runtime.telemetry?.activeThreadCount),
            "health.healthy=" + encode(snapshot.health.healthy),
            "health.degraded=" + encode(snapshot.health.degraded),
            "health.unhealthy=" + encode(snapshot.health.unhealthy),
            "health.recovering=" + encode(snapshot.health.recovering),
            "health.quarantined=" + encode(snapshot.health.quarantined),
            "health.disabled=" + encode(snapshot.health.disabled),
            "health.unknown=" + encode(snapshot.health.unknown),
            "recovery=" + encode(snapshot.recovery.recoveryStateFingerprint),
            "sources=" + encode(snapshot.liveSources.sourceStateFingerprint),
            *result.issues
                .sortedWith(compareBy<SelfObservationIssue> { it.domain.name }.thenBy { it.kind.name }.thenBy { it.detail })
                .map { "issue:${it.domain.name}:${it.kind.name}:${it.detail}" }
                .toTypedArray(),
        )
    }

    private fun canonical(prefix: String, values: Set<String>?): String =
        if (values == null) {
            "$prefix=null"
        } else {
            "$prefix=" + values.map { it.trim().lowercase() }.sorted().joinToString("|")
        }

    private fun ratioBucket(value: Double?): String =
        value?.let { ((it.coerceIn(0.0, 1.0) * RATIO_BUCKETS).toInt()).toString() } ?: "null"

    private fun threadBucket(value: Int?): String =
        value?.let { (it / THREAD_BUCKET_WIDTH).toString() } ?: "null"

    private fun encode(value: Any?): String = value?.toString() ?: "null"

    private fun positive(value: Int?): Boolean = value != null && value > 0

    private companion object {
        const val RATIO_BUCKETS = 20.0
        const val THREAD_BUCKET_WIDTH = 4
    }
}
