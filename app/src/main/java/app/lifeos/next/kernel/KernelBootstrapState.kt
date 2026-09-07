package app.lifeos.next.kernel

import app.lifeos.core.model.Photon

enum class KernelBootstrapStatus {
    CREATED,
    LOADING,
    READY,
    FAILED,
}

data class KernelBootstrapState(
    val status: KernelBootstrapStatus = KernelBootstrapStatus.CREATED,
    val photons: List<Photon> = emptyList(),
    val unreadableFiles: Int = 0,
    val failureMessage: String? = null,
) {
    val loading: Boolean
        get() = status == KernelBootstrapStatus.LOADING

    val ready: Boolean
        get() = status == KernelBootstrapStatus.READY
}
