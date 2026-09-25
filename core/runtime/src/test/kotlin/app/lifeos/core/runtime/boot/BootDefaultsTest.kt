package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
    fun compositeStoreVerifierRunsIndependentProbesConcurrentlyAndSortsResults() = runTest {
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val verifier = CompositeStoreVerifier(
            probes = listOf(
                object : StoreProbe {
                    override val storeId = "z-first"
                    override suspend fun probe(): StoreStatus {
                        withTimeout(1_000) { secondStarted.await() }
                        release.await()
                        return StoreStatus(storeId, StoreState.HEALTHY)
                    }
                },
                object : StoreProbe {
                    override val storeId = "a-second"
                    override suspend fun probe(): StoreStatus {
                        secondStarted.complete(Unit)
                        release.await()
                        return StoreStatus(storeId, StoreState.HEALTHY)
                    }
                },
            ),
            maxConcurrentProbes = 2,
        )

        val verification = async {
            verifier.verify()
        }
        withTimeout(1_000) { secondStarted.await() }
        release.complete(Unit)
        val result = verification.await()

        assertEquals(listOf("a-second", "z-first"), result.stores.map { it.storeId })
        assertTrue(result.canBootNormally)
    }

    @Test
    fun secureRequiredCorruptionRemainsFatal() = runTest {
        val result = CompositeStoreVerifier(
            listOf(
                probe(
                    id = "protection",
                    state = StoreState.CORRUPTED,
                    probeCriticality = BootCriticality.SECURE_REQUIRED,
                )
            )
        ).verify()

        assertFalse(result.canBootNormally)
        assertFalse(result.requiresRecovery)
        val validation = DefaultBootValidator().validate(contextWithStores(result))
        assertIs<BootValidationResult.Fatal>(validation)
    }

    @Test
    fun requiredDegradedCorruptionContinuesAsDegradedRecovery() = runTest {
        val result = CompositeStoreVerifier(
            listOf(
                probe(
                    id = "world-head",
                    state = StoreState.CORRUPTED,
                    probeCriticality = BootCriticality.REQUIRED_DEGRADED,
                )
            )
        ).verify()

        assertFalse(result.canBootNormally)
        assertTrue(result.requiresRecovery)
        val validation = assertIs<BootValidationResult.Degraded>(
            DefaultBootValidator().validate(contextWithStores(result))
        )
        assertTrue("stores-degraded" in validation.limitations)
    }

    @Test
    fun optionalWarmCorruptionDoesNotBlockCriticalBootButIsReportedAsLimitation() = runTest {
        val result = CompositeStoreVerifier(
            listOf(
                probe(
                    id = "generated-tools",
                    state = StoreState.CORRUPTED,
                    probeCriticality = BootCriticality.OPTIONAL_WARM,
                )
            )
        ).verify()

        assertTrue(result.canBootNormally)
        assertFalse(result.requiresRecovery)
        val validation = assertIs<BootValidationResult.Degraded>(
            DefaultBootValidator().validate(contextWithStores(result))
        )
        assertEquals(
            setOf("optional-store-degraded:generated-tools:corrupted"),
            validation.limitations,
        )
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

    private fun contextWithStores(stores: StoreVerificationResult) = BootContext(
        stores = stores,
        runtimeState = RehydratedRuntimeState(),
        photons = PhotonRehydrationResult(
            hot = emptyList(),
            warm = emptyList(),
            cold = emptyList(),
            assessments = emptyList(),
            unreadableFiles = emptyList(),
        ),
        modules = ModuleRestoreSummary(restored = 1),
        thoughtMatrix = ThoughtMatrixWarmupResult(),
        capabilities = CapabilityWarmupResult(availableCapabilities = 1),
    )

    private fun probe(
        id: String,
        state: StoreState,
        probeCriticality: BootCriticality = BootCriticality.SECURE_REQUIRED,
    ) = object : StoreProbe {
        override val storeId: String = id
        override val criticality: BootCriticality = probeCriticality
        override suspend fun probe() = StoreStatus(storeId, state)
    }
}
