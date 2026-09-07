package app.lifeos.core.image.nativebackend

import android.content.Context

/** Runtime gate for the MMSI Vulkan forward-synthesis backends. */
class VulkanMmsiBridge {
    fun isComputeAvailable(): Boolean {
        ensureLoaded()
        return nativeIsVulkanComputeAvailable()
    }

    fun hasCompiledForwardShader(context: Context): Boolean = hasAsset(context, FORWARD_SHADER_ASSET)

    fun hasCompiledSpectralForwardShader(context: Context): Boolean = hasAsset(context, SPECTRAL_FORWARD_SHADER_ASSET)

    fun isReady(context: Context): Boolean = isComputeAvailable() && hasCompiledForwardShader(context)

    fun isSpectralReady(context: Context): Boolean = isComputeAvailable() && hasCompiledSpectralForwardShader(context)

    private fun hasAsset(context: Context, asset: String): Boolean = runCatching {
        context.assets.open(asset).use { input -> input.read() >= 0 }
    }.getOrDefault(false)

    private external fun nativeIsVulkanComputeAvailable(): Boolean

    companion object {
        const val FORWARD_SHADER_ASSET = "shaders/mmsi_forward.comp.spv"
        const val SPECTRAL_FORWARD_SHADER_ASSET = "shaders/mmsi_spectral_forward.comp.spv"

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
