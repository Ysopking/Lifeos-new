package app.lifeos.core.runtime.boot

interface StoreProbe {
    val storeId: String
    suspend fun probe(): StoreStatus
}

class CompositeStoreVerifier(
    private val probes: List<StoreProbe>,
) : StoreVerifier {
    init {
        require(probes.map { it.storeId }.distinct().size == probes.size) {
            "Store probe ids must be unique"
        }
    }

    override suspend fun verify(): StoreVerificationResult {
        val statuses = probes.map { probe ->
            runCatching { probe.probe() }
                .getOrElse { error ->
                    StoreStatus(
                        storeId = probe.storeId,
                        state = StoreState.UNAVAILABLE,
                        message = error.message ?: error::class.simpleName,
                    )
                }
        }
        val fatal = statuses.any { it.state == StoreState.CORRUPTED || it.state == StoreState.VERSION_MISMATCH }
        val recoverable = statuses.any {
            it.state == StoreState.STALE ||
                it.state == StoreState.PARTIALLY_RECOVERABLE ||
                it.state == StoreState.LOCKED ||
                it.state == StoreState.UNAVAILABLE
        }
        return StoreVerificationResult(
            stores = statuses,
            canBootNormally = !fatal && !recoverable,
            requiresRecovery = !fatal && recoverable,
        )
    }
}

fun interface BootDeltaSource {
    suspend fun count(context: BootContext): Long
}

class CompositeBootDeltaDetector(
    private val sources: List<BootDeltaSource>,
) : BootDeltaDetector {
    override suspend fun detect(context: BootContext): Long = sources.sumOf { source ->
        val count = source.count(context)
        require(count >= 0) { "Boot delta sources must not return negative counts" }
        count
    }
}

class DefaultBootValidator : BootValidator {
    override suspend fun validate(context: BootContext): BootValidationResult {
        val fatalStores = context.stores.stores.filter {
            it.state == StoreState.CORRUPTED || it.state == StoreState.VERSION_MISMATCH
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
