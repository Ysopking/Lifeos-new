package app.lifeos.core.runtime.boot

import kotlinx.coroutines.CancellationException

enum class WarmRuntimeFeature {
    PERSONAL_RUNTIME,
    DEEP_SEARCH,
    SELF_HEALING,
    DURABLE_GOALS,
    GENERATED_TOOLS,
    SENSORS,
}

enum class WarmRuntimeFeatureStatus {
    NOT_REQUIRED,
    WARMING,
    READY,
    FAILED,
}

data class WarmRuntimeFeatureState(
    val status: WarmRuntimeFeatureStatus,
    val failure: String? = null,
) {
    init {
        require(failure == null || failure.isNotBlank())
        require(status == WarmRuntimeFeatureStatus.FAILED || failure == null) {
            "Warm feature failure text requires FAILED state"
        }
    }
}

object WarmRuntimeReadinessRegistry {
    private val lock = Any()

    @Volatile
    private var states: Map<WarmRuntimeFeature, WarmRuntimeFeatureState> = emptyMap()

    fun beginBoot(features: Set<WarmRuntimeFeature>) {
        synchronized(lock) {
            states = WarmRuntimeFeature.entries.associateWith { feature ->
                WarmRuntimeFeatureState(
                    status = if (feature in features) {
                        WarmRuntimeFeatureStatus.WARMING
                    } else {
                        WarmRuntimeFeatureStatus.NOT_REQUIRED
                    }
                )
            }
        }
    }

    fun markReady(feature: WarmRuntimeFeature) {
        synchronized(lock) {
            if (stateLocked(feature).status == WarmRuntimeFeatureStatus.NOT_REQUIRED) return
            states = states + (feature to WarmRuntimeFeatureState(WarmRuntimeFeatureStatus.READY))
        }
    }

    fun markFailed(feature: WarmRuntimeFeature, failure: String) {
        require(failure.isNotBlank())
        synchronized(lock) {
            if (stateLocked(feature).status == WarmRuntimeFeatureStatus.NOT_REQUIRED) return
            states = states + (
                feature to WarmRuntimeFeatureState(
                    status = WarmRuntimeFeatureStatus.FAILED,
                    failure = failure,
                )
            )
        }
    }

    fun state(feature: WarmRuntimeFeature): WarmRuntimeFeatureState =
        synchronized(lock) { stateLocked(feature) }

    fun requireReady(feature: WarmRuntimeFeature) {
        val current = state(feature)
        when (current.status) {
            WarmRuntimeFeatureStatus.NOT_REQUIRED,
            WarmRuntimeFeatureStatus.READY -> Unit
            WarmRuntimeFeatureStatus.WARMING,
            WarmRuntimeFeatureStatus.FAILED -> error(
                buildString {
                    append("Warm runtime feature is not ready: ")
                    append(feature.name)
                    append(":")
                    append(current.status.name)
                    current.failure?.let {
                        append(":")
                        append(it)
                    }
                }
            )
        }
    }

    suspend fun <T> runWarmup(
        feature: WarmRuntimeFeature,
        action: suspend () -> T,
    ): T {
        return try {
            action().also { markReady(feature) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            markFailed(
                feature,
                error.message?.takeIf { it.isNotBlank() }
                    ?: error::class.simpleName
                    ?: "warm-feature-failed",
            )
            throw error
        }
    }

    internal fun clearForTests() {
        synchronized(lock) {
            states = emptyMap()
        }
    }

    private fun stateLocked(feature: WarmRuntimeFeature): WarmRuntimeFeatureState =
        states[feature] ?: WarmRuntimeFeatureState(WarmRuntimeFeatureStatus.NOT_REQUIRED)
}
