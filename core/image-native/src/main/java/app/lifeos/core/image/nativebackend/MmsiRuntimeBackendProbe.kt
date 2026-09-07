package app.lifeos.core.image.nativebackend

import android.content.Context
import app.lifeos.core.image.MmsiExecutionPath
import app.lifeos.core.image.MmsiRuntimeBackendSelection
import app.lifeos.core.image.MmsiRuntimeCapabilities
import java.io.Closeable

/**
 * One-time device probe for MMSI execution capabilities.
 * Renderer instances created during probing are immediately closed; only the immutable capability
 * snapshot is cached, so no Vulkan/HardwareBuffer resource is retained by the probe itself.
 */
class MmsiRuntimeBackendProbe(context: Context) {
    data class Snapshot(
        val capabilities: MmsiRuntimeCapabilities,
        val selectedPath: MmsiExecutionPath,
    )

    private val appContext = context.applicationContext
    private val cached: Snapshot by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { probeNow() }

    fun snapshot(): Snapshot = cached

    private fun probeNow(): Snapshot {
        val capabilities = MmsiRuntimeCapabilities(
            spectralAhbSyncFd = probeCloseable {
                VulkanSpectralSyncFdHardwareBufferMmsiRenderer.create(appContext)
            },
            rgbAhbSyncFd = probeCloseable {
                VulkanSyncFdHardwareBufferMmsiRenderer.create(appContext)
            },
            rgbAhbSync = probeCloseable {
                VulkanHardwareBufferMmsiRenderer.create(appContext)
            },
            vulkanStaging = probeCloseable {
                VulkanMmsiRenderer.create(appContext)
            },
            nativePhase1 = NativeMmsiBridge().isAvailable(),
            cpuReference = true,
        )
        return Snapshot(
            capabilities = capabilities,
            selectedPath = MmsiRuntimeBackendSelection.select(capabilities),
        )
    }

    private inline fun <T : Closeable> probeCloseable(factory: () -> T?): Boolean = runCatching {
        val value = factory() ?: return@runCatching false
        value.use { true }
    }.getOrDefault(false)
}
