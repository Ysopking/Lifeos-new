package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory

/** Maps the existing runtime failure surface into the richer health domain. */
class FailureClassifier {
    fun classify(failure: RuntimeFailure): FailureClassification {
        val scope = inferScope(failure)
        val category = when (failure.category) {
            RuntimeFailureCategory.FIELD -> HealthFailureCategory.FIELD
            RuntimeFailureCategory.TIMEOUT -> HealthFailureCategory.TIMEOUT
            RuntimeFailureCategory.STORAGE -> HealthFailureCategory.STORAGE_IO
            RuntimeFailureCategory.INVARIANT -> HealthFailureCategory.STATE_INVARIANT
            RuntimeFailureCategory.UNKNOWN -> HealthFailureCategory.UNKNOWN
        }

        val recoverable = failure.recoverable && category != HealthFailureCategory.STATE_INVARIANT
        val suggestedState = if (recoverable) HealthState.DEGRADED else HealthState.UNHEALTHY

        return FailureClassification(
            category = category,
            scope = scope,
            recoverable = recoverable,
            suggestedState = suggestedState,
        )
    }

    private fun inferScope(failure: RuntimeFailure): HealthScope {
        val source = failure.source.lowercase()
        return when {
            failure.category == RuntimeFailureCategory.FIELD || "field" in source -> HealthScope.FIELD
            "checkpoint" in source ||
                "photon" in source ||
                "task-repository" in source ||
                "vault" in source ||
                "storage" in source ||
                "repository" in source -> HealthScope.STORAGE_ENGINE
            "scheduler" in source -> HealthScope.SCHEDULER
            "worker" in source -> HealthScope.WORKER
            "recovery" in source -> HealthScope.RUNTIME
            "runtime" in source -> HealthScope.RUNTIME
            "kernel" in source || "boot" in source -> HealthScope.KERNEL
            else -> HealthScope.UNKNOWN
        }
    }
}
