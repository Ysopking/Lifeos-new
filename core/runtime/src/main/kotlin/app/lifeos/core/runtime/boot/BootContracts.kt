package app.lifeos.core.runtime.boot

interface RuntimeBootstrapper {
    suspend fun bootstrap()
}

interface StoreVerifier {
    suspend fun verify(): StoreVerificationResult
}

interface StateRehydrator {
    suspend fun rehydrate(): RehydratedRuntimeState
}

interface ModuleRehydrator {
    suspend fun rehydrate(): ModuleRestoreSummary
}

interface ThoughtMatrixWarmup {
    suspend fun warmup(): ThoughtMatrixWarmupResult
}

interface CapabilityWarmup {
    suspend fun warmup(): CapabilityWarmupResult
}

interface BootValidator {
    suspend fun validate(context: BootContext): BootValidationResult
}

interface BootDeltaDetector {
    suspend fun detect(context: BootContext): Long
}

fun interface BootStateSink {
    suspend fun record(snapshot: BootSnapshot)
}

object NoOpBootStateSink : BootStateSink {
    override suspend fun record(snapshot: BootSnapshot) = Unit
}
