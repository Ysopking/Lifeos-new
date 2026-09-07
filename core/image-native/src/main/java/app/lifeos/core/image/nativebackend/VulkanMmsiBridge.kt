package app.lifeos.core.image.nativebackend

import android.content.Context

/** Runtime gate for the MMSI Vulkan forward-synthesis backend. */
class VulkanMmsiBridge {
    fun isComputeAvailable(): Boolean {
        ensureLoaded()
        return nativeIsVulkanComputeAvailable()
    }

    fun hasCompiledForwardShader(context: Context): Boolean = runCatching {
        context.assets.open(FORWARD_SHADER_ASSET).use { input -> input.read() >= 0 }
    }.getOrDefault(false)

    fun isReady(context: Context): Boolean = isComputeAvailable() && hasCompiledForwardShader(context)

    private external fun nativeIsVulkanComputeAvailable(): Boolean

    companion object {
        const val FORWARD_SHADER_ASSET = "shaders/mmsi_forward.comp.spv"

        @Volatile private var loaded = false

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("lifeos_mmsi_native")
                    loaded = true
                }
            }
        }
    }
}
