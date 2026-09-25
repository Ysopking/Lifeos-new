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
    val photons: List<Photon> = emptyList(),
    val unreadableFiles: Int = 0,
    val warnings: List<String> = emptyList(),
    val failureMessage: String? = null,
) {
    val availability: RuntimeAvailability
        get() = when (status) {
            KernelBootstrapStatus.READY -> RuntimeAvailability.FULL
            KernelBootstrapStatus.DEGRADED -> RuntimeAvailability.DEGRADED
            KernelBootstrapStatus.READ_ONLY -> RuntimeAvailability.READ_ONLY
            KernelBootstrapStatus.RECOVERY,
            KernelBootstrapStatus.CREATED,
            KernelBootstrapStatus.LOADING -> RuntimeAvailability.RECOVERY
            KernelBootstrapStatus.SAFE_MODE,
            KernelBootstrapStatus.FAILED -> RuntimeAvailability.SAFE_MODE
        }

    val loading: Boolean
        get() = status == KernelBootstrapStatus.LOADING ||
            status == KernelBootstrapStatus.RECOVERY

    val ready: Boolean
        get() = status == KernelBootstrapStatus.READY ||
            status == KernelBootstrapStatus.DEGRADED

    val readable: Boolean
        get() = ready || status == KernelBootstrapStatus.READ_ONLY

    val writable: Boolean
        get() = ready

    val actionable: Boolean
        get() = ready

    val usable: Boolean
        get() = readable || status == KernelBootstrapStatus.SAFE_MODE
}
