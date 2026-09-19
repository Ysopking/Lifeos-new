package app.lifeos.next.kernel

import app.lifeos.core.model.Photon

enum class KernelBootstrapStatus {
    CREATED,
    LOADING,
    READY,
    DEGRADED,
    FAILED,
}

data class KernelBootstrapState(
    val status: KernelBootstrapStatus = KernelBootstrapStatus.CREATED,
    val photons: List<Photon> = emptyList(),
    val durablePhotonCount: Long = photons.size.toLong(),
    val coldPhotonCount: Int = 0,
    val unreadableFiles: Int = 0,
    val warnings: List<String> = emptyList(),
    val failureMessage: String? = null,
) {
    val loading: Boolean
        get() = status == KernelBootstrapStatus.LOADING

    val ready: Boolean
        get() = status == KernelBootstrapStatus.READY || status == KernelBootstrapStatus.DEGRADED

    val retainedPhotonCount: Int
        get() = photons.size

    val workingSetTruncated: Boolean
        get() = durablePhotonCount > photons.size.toLong()
}
