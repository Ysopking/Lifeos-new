package app.lifeos.core.runtime.boot

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

class BootCoordinator(
    private val runtimeBootstrapper: RuntimeBootstrapper,
    private val storeVerifier: StoreVerifier,
    private val stateRehydrator: StateRehydrator,
    private val photonRehydrator: PhotonRehydrator,
    private val moduleRehydrator: ModuleRehydrator,
    private val thoughtMatrixWarmup: ThoughtMatrixWarmup,
    private val capabilityWarmup: CapabilityWarmup,
    private val deltaDetector: BootDeltaDetector,
    private val validator: BootValidator,
    private val stateSink: BootStateSink = NoOpBootStateSink,
    private val now: () -> Instant = Instant::now,
    private val newBootId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun boot(): BootRunResult {
        var snapshot = BootSnapshot(
            bootId = newBootId(),
            startedAt = now(),
            state = BootState.NOT_STARTED,
        )

        suspend fun transition(
            state: BootState,
            mutate: (BootSnapshot) -> BootSnapshot = { it },
        ) {
            snapshot = mutate(snapshot).copy(state = state)
            stateSink.record(snapshot)
        }

        return try {
            transition(BootState.INITIALIZING)
            runtimeBootstrapper.bootstrap()

            transition(BootState.VERIFYING_STORES)
            val stores = storeVerifier.verify()
            if (!stores.canBootNormally && !stores.requiresRecovery) {
                val failures = stores.stores
                    .filter { it.state != StoreState.HEALTHY }
                    .map { "${it.storeId}:${it.state}" }
                transition(BootState.FAILED) { it.copy(failures = it.failures + failures) }
                return BootRunResult.Failed(
                    snapshot,
                    IllegalStateException("Store verification prevents boot"),
                )
            }

            transition(
                if (stores.requiresRecovery) BootState.RECOVERING else BootState.RESTORING_RUNTIME
            )
            val runtimeState = stateRehydrator.rehydrate()
            snapshot = snapshot.copy(lastCheckpointId = runtimeState.checkpointId)
            if (stores.requiresRecovery) {
                transition(BootState.RESTORING_RUNTIME)
            }

            transition(BootState.RESTORING_PHOTONS)
            val photons = photonRehydrator.rehydrate()
            snapshot = snapshot.copy(
                restoredPhotonCount = photons.restoredCount,
                warnings = snapshot.warnings + photons.unreadableFiles.map { "unreadable-photon:$it" },
            )

            transition(BootState.RESTORING_MODULES)
            val modules = moduleRehydrator.rehydrate()
            snapshot = snapshot.copy(
                restoredModuleCount = modules.restored,
                warnings = snapshot.warnings + if (modules.degraded > 0) {
                    listOf("degraded-modules:${modules.degraded}")
                } else {
                    emptyList()
                },
            )

            transition(BootState.WARMING_COGNITION)
            val matrix = thoughtMatrixWarmup.warmup()
            val capabilities = capabilityWarmup.warmup()

            val preDeltaContext = BootContext(
                stores = stores,
                runtimeState = runtimeState,
                photons = photons,
                modules = modules,
                thoughtMatrix = matrix,
                capabilities = capabilities,
            )

            transition(BootState.ANALYZING_DELTAS)
            val deltaCount = deltaDetector.detect(preDeltaContext)
            require(deltaCount >= 0) { "Detected delta count must not be negative" }
            snapshot = snapshot.copy(detectedDeltaCount = deltaCount)

            transition(BootState.VALIDATING)
            when (val validation = validator.validate(preDeltaContext)) {
                BootValidationResult.Ready -> {
                    transition(BootState.READY)
                    BootRunResult.Ready(snapshot)
                }

                is BootValidationResult.Degraded -> {
                    transition(BootState.DEGRADED) {
                        it.copy(warnings = it.warnings + validation.limitations.sorted())
                    }
                    BootRunResult.Degraded(snapshot)
                }

                is BootValidationResult.RecoveryRequired -> {
                    transition(BootState.RECOVERING) {
                        it.copy(failures = it.failures + validation.failures)
                    }
                    BootRunResult.RecoveryRequired(snapshot)
                }

                is BootValidationResult.Fatal -> {
                    transition(BootState.FAILED) {
                        it.copy(failures = it.failures + validation.reason)
                    }
                    BootRunResult.Failed(
                        snapshot,
                        IllegalStateException(validation.reason),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            transition(BootState.FAILED) {
                it.copy(failures = it.failures + (error.message ?: error::class.simpleName.orEmpty()))
            }
            BootRunResult.Failed(snapshot, error)
        }
    }
}
