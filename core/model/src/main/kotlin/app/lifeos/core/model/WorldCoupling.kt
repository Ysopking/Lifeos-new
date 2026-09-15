package app.lifeos.core.model

enum class WorldCouplingKind { SEMANTIC, CAUSAL, TEMPORAL, RESOURCE, GOAL, MEMORY, CONTRADICTION, DEPENDENCY }

data class WorldCoupling(
    val sourceKey: String,
    val targetKey: String,
    val kind: WorldCouplingKind,
    val strengthMicros: Long,
    val evidenceFingerprint: String,
) {
    init {
        require(sourceKey.isNotBlank() && targetKey.isNotBlank() && sourceKey != targetKey)
        require(strengthMicros in -1_000_000L..1_000_000L)
        require(evidenceFingerprint.isNotBlank())
    }
    val stableFingerprint: String get() = StableCognitiveIds.fingerprint(sourceKey, targetKey, kind.name, strengthMicros.toString(), evidenceFingerprint)
}

data class ModuleEpistemicSnapshot(
    val module: ModuleIdentity,
    val expertiseMicros: Long,
    val calibrationMicros: Long,
    val coverageMicros: Long,
    val reliabilityMicros: Long,
) {
    init { listOf(expertiseMicros, calibrationMicros, coverageMicros, reliabilityMicros).forEach { require(it in 0..1_000_000L) } }
}
