package app.lifeos.core.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MmsiRuntimeBackendSelectionTest {
    @Test
    fun spectralAsyncPathWinsWhenAvailable() {
        val capabilities = MmsiRuntimeCapabilities(
            spectralAhbSyncFd = true,
            rgbAhbSyncFd = true,
            rgbAhbSync = true,
            vulkanStaging = true,
            nativePhase1 = true,
        )
        assertEquals(
            MmsiExecutionPath.SPECTRAL_AHB_SYNC_FD,
            MmsiRuntimeBackendSelection.select(capabilities),
        )
    }

    @Test
    fun fallsBackInStrictQualityOrder() {
        assertEquals(
            MmsiExecutionPath.RGB_AHB_SYNC_FD,
            MmsiRuntimeBackendSelection.select(MmsiRuntimeCapabilities(rgbAhbSyncFd = true, nativePhase1 = true)),
        )
        assertEquals(
            MmsiExecutionPath.RGB_AHB_SYNC,
            MmsiRuntimeBackendSelection.select(MmsiRuntimeCapabilities(rgbAhbSync = true, nativePhase1 = true)),
        )
        assertEquals(
            MmsiExecutionPath.VULKAN_STAGING,
            MmsiRuntimeBackendSelection.select(MmsiRuntimeCapabilities(vulkanStaging = true, nativePhase1 = true)),
        )
        assertEquals(
            MmsiExecutionPath.NATIVE_PHASE1,
            MmsiRuntimeBackendSelection.select(MmsiRuntimeCapabilities(nativePhase1 = true)),
        )
        assertEquals(
            MmsiExecutionPath.CPU_REFERENCE,
            MmsiRuntimeBackendSelection.select(MmsiRuntimeCapabilities()),
        )
    }

    @Test
    fun capabilitySupportMappingIsExplicit() {
        val capabilities = MmsiRuntimeCapabilities(
            spectralAhbSyncFd = true,
            nativePhase1 = true,
            cpuReference = false,
        )
        with(MmsiRuntimeBackendSelection) {
            assertTrue(capabilities.supports(MmsiExecutionPath.SPECTRAL_AHB_SYNC_FD))
            assertTrue(capabilities.supports(MmsiExecutionPath.NATIVE_PHASE1))
            assertFalse(capabilities.supports(MmsiExecutionPath.RGB_AHB_SYNC_FD))
            assertFalse(capabilities.supports(MmsiExecutionPath.CPU_REFERENCE))
        }
    }
}
