package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.boot.RuntimeAvailability

enum class KernelBootstrapStatus {
    CREATED,
    LOADING,
    READY,
    DEGRADED,
    READ_ONLY,
    RECOVERY,
    SAFE_MODE,
    FAILED,
}

data class KernelBootstrapState(
    val status: KernelBootstrapStatus = KernelBootstrapStatus.CREATED,
    val availability: RuntimeAvailability = status.defaultAvailability(),
    val photons: List<Photon> = emptyList(),
    val unreadableFiles: Int = 0,
    val warnings: List<String> = emptyList(),
    val failureMessage: String? = null,
) {
    init {
        require(unreadableFiles >= 0)
        require(failureMessage == null || failureMessage.isNotBlank())
    }

    val loading: Boolean
        get() =
            status == KernelBootstrapStatus.LOADING ||
                status == KernelBootstrapStatus.RECOVERY

    val readable: Boolean
        get() =
            availability == RuntimeAvailability.FULL ||
                availability == RuntimeAvailability.DEGRADED ||
                availability == RuntimeAvailability.READ_ONLY

    val writable: Boolean
        get() =
            availability == RuntimeAvailability.FULL ||
                availability == RuntimeAvailability.DEGRADED

    val actionable: Boolean
        get() = writable

    val ready: Boolean
        get() = writable
}

private fun KernelBootstrapStatus.defaultAvailability(): RuntimeAvailability = when (this) {
    KernelBootstrapStatus.CREATED,
    KernelBootstrapStatus.LOADING,
    KernelBootstrapStatus.RECOVERY -> RuntimeAvailability.RECOVERY
    KernelBootstrapStatus.READY -> RuntimeAvailability.FULL
    KernelBootstrapStatus.DEGRADED -> RuntimeAvailability.DEGRADED
    KernelBootstrapStatus.READ_ONLY -> RuntimeAvailability.READ_ONLY
    KernelBootstrapStatus.SAFE_MODE,
    KernelBootstrapStatus.FAILED -> RuntimeAvailability.SAFE_MODE
}
