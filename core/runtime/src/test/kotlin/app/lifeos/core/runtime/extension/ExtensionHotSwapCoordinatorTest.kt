package app.lifeos.core.runtime.extension

import app.lifeos.core.runtime.evolution.ControlledEvolutionSubjectKind
import app.lifeos.core.runtime.evolution.ControlledEvolutionSubjectRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExtensionHotSwapCoordinatorTest {
    @Test
    fun appliesAuthorizedSnapshotThroughHeadCasAndRollsBackExactPredecessor() = runTest {
        val initial = snapshot("initial")
        val candidate = snapshot("candidate")
        val initialHead = ExtensionRegistryHead.create(
            revision = 1,
            snapshot = initial,
            predecessorSnapshotId = null,
        )
        val heads = InMemoryHeads(initialHead)
        val snapshots = InMemorySnapshots(initial, candidate)
        val subject = ControlledEvolutionSubjectRef.create(
            kind = ControlledEvolutionSubjectKind.SEMANTIC_EXTENSION,
            candidateId = "candidate-1",
            sourceArtifactId = "artifact-1",
            validationBundleId = "validation-1",
            candidateFingerprint = "candidate-fingerprint",
        )

        val coordinator = ExtensionHotSwapCoordinator(
            heads = heads,
            snapshots = snapshots,
            hotSwapAuthority = ExtensionHotSwapAuthority { actualSubject, current, target ->
                ExtensionHotSwapAuthorization.create(
                    subjectId = actualSubject.id,
                    promotionEvidenceId = "promotion-evidence-1",
                    expectedHeadFingerprint = current.fingerprint,
                    targetSnapshotId = target.id,
                    targetSnapshotFingerprint = target.fingerprint(),
                )
            },
            rollbackAuthority = ExtensionRollbackAuthority { current, restore ->
                ExtensionRollbackAuthorization.create(
                    rollbackEvidenceId = "rollback-evidence-1",
                    expectedHeadFingerprint = current.fingerprint,
                    restoreSnapshotId = restore.id,
                    restoreSnapshotFingerprint = restore.fingerprint(),
                )
            },
        )

        val applied = assertIs<ExtensionHotSwapResult.Applied>(
            coordinator.apply(subject, initialHead, candidate)
        )
        assertEquals(candidate.id, applied.currentHead.activeSnapshotId)
        assertEquals(initial.id, applied.currentHead.predecessorSnapshotId)

        val rolledBack = assertIs<ExtensionHotSwapResult.RolledBack>(
            coordinator.rollback(applied.currentHead)
        )
        assertEquals(initial.id, rolledBack.currentHead.activeSnapshotId)
        assertEquals(candidate.id, rolledBack.currentHead.predecessorSnapshotId)
    }

    @Test
    fun staleExpectedHeadCannotSwap() = runTest {
        val initial = snapshot("initial")
        val other = snapshot("other")
        val candidate = snapshot("candidate")
        val initialHead = ExtensionRegistryHead.create(1, initial, null)
        val liveHead = ExtensionRegistryHead.create(2, other, initial.id)
        val coordinator = ExtensionHotSwapCoordinator(
            heads = InMemoryHeads(liveHead),
            snapshots = InMemorySnapshots(initial, other, candidate),
            hotSwapAuthority = ExtensionHotSwapAuthority { _, _, _ -> error("must not authorize") },
            rollbackAuthority = ExtensionRollbackAuthority { _, _ -> error("not used") },
        )
        val subject = ControlledEvolutionSubjectRef.create(
            kind = ControlledEvolutionSubjectKind.SEMANTIC_EXTENSION,
            candidateId = "candidate-1",
            sourceArtifactId = "artifact-1",
            validationBundleId = "validation-1",
            candidateFingerprint = "candidate-fingerprint",
        )

        assertIs<ExtensionHotSwapResult.ConcurrentHeadChanged>(
            coordinator.apply(subject, initialHead, candidate)
        )
    }

    private fun snapshot(name: String): ExtensionRegistrySnapshot =
        ExtensionRegistrySnapshot.create(
            listOf(
                ExtensionRegistryEntry(
                    manifest = ExtensionManifest(
                        extensionId = ExtensionId("extension.$name"),
                        version = ExtensionVersion("1.0.0"),
                        kind = ExtensionKind.WORLD_SIGNAL_PACK,
                        providerId = "provider.$name",
                        entrypoints = setOf(
                            ExtensionEntrypoint("contract.$name", "implementation.$name")
                        ),
                    ),
                    worldContract = ExtensionWorldContract(
                        worldSignalSchemaVersion = WorldSignalSchemaVersion(1, 0),
                        worldNodeSchemaVersion = WorldNodeSchemaVersion(1, 0),
                        worldEquationVersion = WorldEquationVersion(
                            "lifeos-world-informational-v1",
                            1,
                            0,
                        ),
                        coefficientSchemaFingerprint = CoefficientSchemaFingerprint("coeff-v1"),
                        projectionContractFingerprint = ProjectionContractFingerprint("projection-v1"),
                    ),
                )
            )
        )

    private class InMemorySnapshots(
        vararg initial: ExtensionRegistrySnapshot,
    ) : ExtensionRegistrySnapshotRepository {
        private val byId = initial.associateBy { it.id }.toMutableMap()

        override suspend fun save(snapshot: ExtensionRegistrySnapshot) {
            byId[snapshot.id]?.let { existing ->
                require(existing == snapshot)
            }
            byId[snapshot.id] = snapshot
        }

        override suspend fun load(id: String): ExtensionRegistrySnapshot? = byId[id]
    }

    private class InMemoryHeads(
        private var head: ExtensionRegistryHead?,
    ) : ExtensionRegistryHeadRepository {
        override suspend fun load(): ExtensionRegistryHead? = head

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: ExtensionRegistryHead,
        ): Boolean {
            val currentRevision = head?.revision
            if (currentRevision != expectedRevision) return false
            head = next
            return true
        }
    }
}
