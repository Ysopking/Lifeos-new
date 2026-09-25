package app.lifeos.core.runtime.boot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

interface StoreProbe {
    val storeId: String
    val criticality: BootCriticality
        get() = BootCriticality.SECURE_REQUIRED

    suspend fun probe(): StoreStatus
}

class CompositeStoreVerifier(
    private val probes: List<StoreProbe>,
    private val maxConcurrentProbes: Int = DEFAULT_MAX_CONCURRENT_PROBES,
) : StoreVerifier {
    init {
        require(probes.map { it.storeId }.distinct().size == probes.size) {
            "Store probe ids must be unique"
        }
        require(maxConcurrentProbes in 1..MAX_CONCURRENT_PROBES) {
            "Store verifier concurrency must be bounded"
        }
    }

    override suspend fun verify(): StoreVerificationResult = coroutineScope {
        val semaphore = Semaphore(maxConcurrentProbes)
        val statuses = probes.map { probe ->
            async {
                semaphore.withPermit {
                    try {
                        probe.probe().copy(criticality = probe.criticality)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        StoreStatus(
                            storeId = probe.storeId,
                            state = StoreState.UNAVAILABLE,
                            message = error.message ?: error::class.simpleName,
                            criticality = probe.criticality,
                        )
                    }
                }
            }
        }.awaitAll().sortedBy { it.storeId }

        val fatal = statuses.any {
            it.criticality == BootCriticality.SECURE_REQUIRED &&
                (it.state == StoreState.CORRUPTED || it.state == StoreState.VERSION_MISMATCH)
        }
        val recoverable = statuses.any {
            it.criticality != BootCriticality.OPTIONAL_WARM &&
                (
                    it.state == StoreState.STALE ||
                        it.state == StoreState.PARTIALLY_RECOVERABLE ||
                        it.state == StoreState.LOCKED ||
                        it.state == StoreState.UNAVAILABLE ||
                        (
                            it.criticality == BootCriticality.REQUIRED_DEGRADED &&
                                (
                                    it.state == StoreState.CORRUPTED ||
                                        it.state == StoreState.VERSION_MISMATCH
                                    )
                            )
                    )
        }
        StoreVerificationResult(
            stores = statuses,
            canBootNormally = !fatal && !recoverable,
            requiresRecovery = !fatal && recoverable,
        )
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_PROBES = 4
        const val MAX_CONCURRENT_PROBES = 16
    }
}

fun interface BootDeltaSource {
    suspend fun count(context: BootContext): Long
}

class CompositeBootDeltaDetector(
    private val sources: List<BootDeltaSource>,
) : BootDeltaDetector {
    override suspend fun detect(context: BootContext): Long {
        var total = 0L
        for (source in sources) {
            val count = source.count(context)
            require(count >= 0) { "Boot delta sources must not return negative counts" }
            total = Math.addExact(total, count)
        }
        return total
    }
}

class DefaultBootValidator : BootValidator {
    override suspend fun validate(context: BootContext): BootValidationResult {
        val fatalStores = context.stores.stores.filter {
            it.criticality == BootCriticality.SECURE_REQUIRED &&
                (it.state == StoreState.CORRUPTED || it.state == StoreState.VERSION_MISMATCH)
        }
        if (fatalStores.isNotEmpty()) {
            return BootValidationResult.Fatal(
                reason = fatalStores.joinToString(",") { "${it.storeId}:${it.state}" },
            )
        }

        val recoveryFailures = buildList {
            if (context.stores.requiresRecovery) add("store-recovery-required")
            if (context.modules.failed > 0) add("failed-modules:${context.modules.failed}")
            if (context.photons.quarantined.isNotEmpty()) {
                add("quarantined-photons:${context.photons.quarantined.size}")
            }
        }
        if (context.modules.failed > 0) {
            return BootValidationResult.RecoveryRequired(recoveryFailures)
        }

        val limitations = buildSet {
            if (context.stores.requiresRecovery) add("stores-degraded")
            context.stores.stores
                .filter {
                    it.criticality == BootCriticality.OPTIONAL_WARM &&
                        it.state != StoreState.HEALTHY
                }
                .sortedBy { it.storeId }
                .forEach {
                    add("optional-store-degraded:${it.storeId}:${it.state.name.lowercase()}")
                }
            if (context.runtimeState.degradedRehydrationNodeIds.isNotEmpty()) {
                add(
                    "rehydration-degraded:" +
                        context.runtimeState.degradedRehydrationNodeIds.sorted().joinToString(",")
                )
            }
            if (context.runtimeState.warmFailureNodeIds.isNotEmpty()) {
                add(
                    "warm-rehydration-failed:" +
                        context.runtimeState.warmFailureNodeIds.sorted().joinToString(",")
                )
            }
            if (context.modules.degraded > 0) add("modules-degraded:${context.modules.degraded}")
            if (context.thoughtMatrix.degraded) add("thought-matrix-degraded")
            if (context.capabilities.degradedCapabilities > 0) {
                add("capabilities-degraded:${context.capabilities.degradedCapabilities}")
            }
            if (context.photons.unreadableFiles.isNotEmpty()) {
                add("unreadable-photons:${context.photons.unreadableFiles.size}")
            }
            if (context.photons.quarantined.isNotEmpty()) {
                add("quarantined-photons:${context.photons.quarantined.size}")
            }
        }

        return if (limitations.isEmpty()) {
            BootValidationResult.Ready
        } else {
            BootValidationResult.Degraded(limitations)
        }
    }
}
