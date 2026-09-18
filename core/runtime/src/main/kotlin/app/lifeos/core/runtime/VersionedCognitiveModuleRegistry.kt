package app.lifeos.core.runtime

import app.lifeos.core.field.StableFieldIds

data class CognitiveModuleSnapshot private constructor(
    val id: String,
    val revision: Long,
    val extensionSnapshotId: String,
    val moduleFingerprints: List<String>,
    val predecessorSnapshotId: String?,
) {
    init {
        require(revision > 0L)
        require(extensionSnapshotId.isNotBlank())
        require(moduleFingerprints.isNotEmpty())
        require(moduleFingerprints == moduleFingerprints.distinct().sorted())
        require(predecessorSnapshotId == null || predecessorSnapshotId.isNotBlank())
        require(id == expectedId())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "cognitive-module-snapshot/v1",
        revision.toString(),
        extensionSnapshotId,
        predecessorSnapshotId.orEmpty(),
        *moduleFingerprints.toTypedArray(),
    )

    private fun expectedId(): String = "cognitive-modules:${fingerprint()}"

    companion object {
        fun restore(
            id: String,
            revision: Long,
            extensionSnapshotId: String,
            moduleFingerprints: List<String>,
            predecessorSnapshotId: String?,
        ): CognitiveModuleSnapshot = CognitiveModuleSnapshot(
            id = id,
            revision = revision,
            extensionSnapshotId = extensionSnapshotId,
            moduleFingerprints = moduleFingerprints,
            predecessorSnapshotId = predecessorSnapshotId,
        )

        fun create(
            revision: Long,
            extensionSnapshotId: String,
            modules: Collection<CognitiveModule>,
            predecessorSnapshotId: String?,
        ): CognitiveModuleSnapshot {
            val fingerprints = modules.map {
                it.descriptor.identity.stableFingerprint
            }.distinct().sorted()
            require(fingerprints.isNotEmpty())
            val fp = StableFieldIds.fingerprint(
                "cognitive-module-snapshot/v1",
                revision.toString(),
                extensionSnapshotId,
                predecessorSnapshotId.orEmpty(),
                *fingerprints.toTypedArray(),
            )
            return CognitiveModuleSnapshot(
                id = "cognitive-modules:$fp",
                revision = revision,
                extensionSnapshotId = extensionSnapshotId,
                moduleFingerprints = fingerprints,
                predecessorSnapshotId = predecessorSnapshotId,
            )
        }
    }
}

data class CognitiveModuleHead(
    val revision: Long,
    val activeSnapshotId: String,
    val fingerprint: String,
) {
    init {
        require(revision > 0L)
        require(activeSnapshotId.isNotBlank())
        require(fingerprint.isNotBlank())
    }
}

interface CognitiveModuleSnapshotRepository {
    suspend fun save(snapshot: CognitiveModuleSnapshot)
    suspend fun load(id: String): CognitiveModuleSnapshot?
    suspend fun loadHead(): CognitiveModuleHead?
    suspend fun compareAndSetHead(expectedRevision: Long?, next: CognitiveModuleHead): Boolean
}

fun interface CognitiveModuleResolver {
    fun resolve(stableFingerprint: String): CognitiveModule?
}

class VersionedCognitiveModuleRegistry(
    builtIns: Collection<CognitiveModule>,
    private val snapshots: CognitiveModuleSnapshotRepository,
    private val resolver: CognitiveModuleResolver = CognitiveModuleResolver { null },
) : CognitiveModuleRegistry {
    private val lock = Any()
    private val builtInModules = builtIns.associateBy {
        it.descriptor.identity.stableFingerprint
    }.also { require(it.size == builtIns.size) }
    private val promotedModules = linkedMapOf<String, CognitiveModule>()

    override fun activeModules(): List<CognitiveModule> =
        synchronized(lock) {
            (builtInModules.values + promotedModules.values)
                .distinctBy { it.descriptor.identity.stableFingerprint }
        }.sortedWith(
            compareBy<CognitiveModule> { it.descriptor.identity.moduleId }
                .thenBy { it.descriptor.identity.version }
                .thenBy { it.descriptor.identity.stableFingerprint }
        )

    suspend fun modulesForCycle(
        extensionSnapshotId: String,
    ): List<CognitiveModule> {
        val snapshot = snapshotForCycle(extensionSnapshotId)
        return snapshot.moduleFingerprints.map { fingerprint ->
            synchronized(lock) {
                promotedModules[fingerprint] ?: builtInModules[fingerprint]
            } ?: resolver.resolve(fingerprint)
            ?: error("Active cognitive module implementation is unavailable: $fingerprint")
        }.sortedWith(
            compareBy<CognitiveModule> { it.descriptor.identity.moduleId }
                .thenBy { it.descriptor.identity.version }
                .thenBy { it.descriptor.identity.stableFingerprint }
        )
    }

    suspend fun snapshotForCycle(
        extensionSnapshotId: String,
    ): CognitiveModuleSnapshot {
        val head = snapshots.loadHead()
        if (head != null) {
            val active = requireNotNull(snapshots.load(head.activeSnapshotId))
            require(active.extensionSnapshotId == extensionSnapshotId) {
                "Active cognitive module snapshot belongs to another extension snapshot"
            }
            return active
        }
        val created = CognitiveModuleSnapshot.create(
            revision = 1L,
            extensionSnapshotId = extensionSnapshotId,
            modules = activeModules(),
            predecessorSnapshotId = null,
        )
        snapshots.save(created)
        val next = CognitiveModuleHead(
            revision = 1L,
            activeSnapshotId = created.id,
            fingerprint = created.fingerprint(),
        )
        require(snapshots.compareAndSetHead(null, next)) {
            "Concurrent cognitive module head creation"
        }
        return created
    }

    suspend fun promoteNextCycle(
        extensionSnapshotId: String,
        modules: Collection<CognitiveModule>,
        promotionEvidenceFingerprint: String,
    ): CognitiveModuleSnapshot {
        require(promotionEvidenceFingerprint.isNotBlank())
        val head = snapshots.loadHead()
        val previous = head?.let { snapshots.load(it.activeSnapshotId) }
        require(modules.isNotEmpty())
        require(modules.map { it.descriptor.identity.stableFingerprint }.distinct().size == modules.size)
        val candidate = CognitiveModuleSnapshot.create(
            revision = (head?.revision ?: 0L) + 1L,
            extensionSnapshotId = extensionSnapshotId,
            modules = modules,
            predecessorSnapshotId = previous?.id,
        )
        snapshots.save(candidate)
        val next = CognitiveModuleHead(
            revision = candidate.revision,
            activeSnapshotId = candidate.id,
            fingerprint = StableFieldIds.fingerprint(
                candidate.fingerprint(),
                promotionEvidenceFingerprint,
            ),
        )
        require(snapshots.compareAndSetHead(head?.revision, next)) {
            "Cognitive module head changed during promotion"
        }
        synchronized(lock) {
            modules.forEach { module ->
                promotedModules[module.descriptor.identity.stableFingerprint] = module
            }
        }
        require(modulesForCycle(extensionSnapshotId).map {
            it.descriptor.identity.stableFingerprint
        } == candidate.moduleFingerprints) {
            "Promoted cognitive module implementations do not match the persisted snapshot"
        }
        return candidate
    }
}
