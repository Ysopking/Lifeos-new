package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BootDefaultsTest {
    @Test
    fun compositeStoreVerifierSeparatesRecoverableFromFatalStoreStates() = runTest {
        val recoverable = CompositeStoreVerifier(
            listOf(
                probe("photons", StoreState.HEALTHY),
                probe("tasks", StoreState.STALE),
            )
        ).verify()
        assertFalse(recoverable.canBootNormally)
        assertTrue(recoverable.requiresRecovery)

        val fatal = CompositeStoreVerifier(
            listOf(
                probe("photons", StoreState.CORRUPTED),
            )
        ).verify()
        assertFalse(fatal.canBootNormally)
        assertFalse(fatal.requiresRecovery)
    }

    @Test
    fun defaultValidatorDegradesForUnreadablePhotonsWithoutDeclaringFatal() = runTest {
        val context = BootContext(
            stores = StoreVerificationResult(
                stores = listOf(StoreStatus("photons", StoreState.HEALTHY)),
                canBootNormally = true,
                requiresRecovery = false,
            ),
            runtimeState = RehydratedRuntimeState(),
            photons = PhotonRehydrationResult(
                hot = emptyList<Photon>(),
                warm = emptyList(),
                cold = emptyList<PhotonId>(),
                assessments = emptyList(),
                unreadableFiles = listOf("broken.photon"),
            ),
            modules = ModuleRestoreSummary(restored = 1),
            thoughtMatrix = ThoughtMatrixWarmupResult(),
            capabilities = CapabilityWarmupResult(availableCapabilities = 1),
        )

        val result = assertIs<BootValidationResult.Degraded>(DefaultBootValidator().validate(context))
        assertEquals(setOf("unreadable-photons:1"), result.limitations)
    }

    private fun probe(id: String, state: StoreState) = object : StoreProbe {
        override val storeId: String = id
        override suspend fun probe() = StoreStatus(storeId, state)
    }
}
