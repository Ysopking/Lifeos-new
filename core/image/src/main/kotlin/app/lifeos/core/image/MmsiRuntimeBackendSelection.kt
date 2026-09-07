package app.lifeos.core.image

/** Ordered runtime execution paths from the highest-fidelity mobile fast path to reference fallback. */
enum class MmsiExecutionPath(val backend: MmsiBackend) {
    SPECTRAL_AHB_SYNC_FD(MmsiBackend.VULKAN_COMPUTE),
    RGB_AHB_SYNC_FD(MmsiBackend.VULKAN_COMPUTE),
    RGB_AHB_SYNC(MmsiBackend.VULKAN_COMPUTE),
    VULKAN_STAGING(MmsiBackend.VULKAN_COMPUTE),
    NATIVE_PHASE1(MmsiBackend.NATIVE_SIMD),
    CPU_REFERENCE(MmsiBackend.CPU_REFERENCE),
}

data class MmsiRuntimeCapabilities(
    val spectralAhbSyncFd: Boolean = false,
    val rgbAhbSyncFd: Boolean = false,
    val rgbAhbSync: Boolean = false,
    val vulkanStaging: Boolean = false,
    val nativePhase1: Boolean = false,
    val cpuReference: Boolean = true,
)

/**
 * Pure selection policy. Device probing is deliberately outside this class so the priority order
 * stays deterministic, testable, and independent from Android/Vulkan lifecycle details.
 */
object MmsiRuntimeBackendSelection {
    val preferenceOrder: List<MmsiExecutionPath> = listOf(
        MmsiExecutionPath.SPECTRAL_AHB_SYNC_FD,
        MmsiExecutionPath.RGB_AHB_SYNC_FD,
        MmsiExecutionPath.RGB_AHB_SYNC,
        MmsiExecutionPath.VULKAN_STAGING,
        MmsiExecutionPath.NATIVE_PHASE1,
        MmsiExecutionPath.CPU_REFERENCE,
    )

    fun select(capabilities: MmsiRuntimeCapabilities): MmsiExecutionPath =
        preferenceOrder.firstOrNull { path -> capabilities.supports(path) }
            ?: error("MMSI has no usable execution path")

    fun MmsiRuntimeCapabilities.supports(path: MmsiExecutionPath): Boolean = when (path) {
        MmsiExecutionPath.SPECTRAL_AHB_SYNC_FD -> spectralAhbSyncFd
        MmsiExecutionPath.RGB_AHB_SYNC_FD -> rgbAhbSyncFd
        MmsiExecutionPath.RGB_AHB_SYNC -> rgbAhbSync
        MmsiExecutionPath.VULKAN_STAGING -> vulkanStaging
        MmsiExecutionPath.NATIVE_PHASE1 -> nativePhase1
        MmsiExecutionPath.CPU_REFERENCE -> cpuReference
    }
}
