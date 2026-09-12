package app.lifeos.core.runtime.life

import app.lifeos.core.runtime.CognitiveModule

/** Process-owned services introduced by Blocks B-H and Optimization Round 2. */
data class LifeOsIntegratedCognitionSuite(
    val perception: PerceptionFusionEngine = PerceptionFusionEngine(),
    val lifeMemory: BootLifeMemoryRehydrator = BootLifeMemoryRehydrator(),
    val seinEvaluator: SeinModeEvaluator = SeinModeEvaluator(),
    val lifePlanner: LifePlanner = LifePlanner(),
    val futureEvidence: FutureEvidenceEngine = FutureEvidenceEngine(),
    val domainModules: List<CognitiveModule> = DomainCognitionModules.all() + FutureCognitionModule.create(futureEvidence),
    val creativeCapabilities: CreativeCapabilityOrchestrator = CreativeCapabilityOrchestrator(),
    val memoryIntegrity: MemoryIntegrityVerifier = MemoryIntegrityVerifier(),
    val memoryCompactor: CognitiveMemoryCompactor = CognitiveMemoryCompactor(),
    val readiness: LifeOsCompletionReadiness = LifeOsCompletionReadiness(),
    val chaosVerifier: LifeOsChaosVerifier = LifeOsChaosVerifier(),
)

object LifeOsIntegratedCognitionSuiteRegistry {
    private val lock = Any()
    @Volatile private var current: LifeOsIntegratedCognitionSuite? = null

    fun install(suite: LifeOsIntegratedCognitionSuite) = synchronized(lock) {
        current = suite
    }

    fun current(): LifeOsIntegratedCognitionSuite? = current

    internal fun clearForTests() = synchronized(lock) {
        current = null
    }
}
