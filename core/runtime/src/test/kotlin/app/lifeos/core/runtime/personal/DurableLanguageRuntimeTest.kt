package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LinguisticLexiconSnapshot
import app.lifeos.core.language.VersionedLanguageRuntime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurableLanguageRuntimeTest {
    @Test
    fun `promotion survives fresh runtime rehydrate with exact snapshot`() = runTest {
        val repository = InMemoryLanguageRuntimeStateRepository()
        val firstRuntime = VersionedLanguageRuntime()
        val first = DurableLanguageRuntimeCoordinator(firstRuntime, repository)

        val baseline = first.rehydrate().lexicon
        val continueConcept = baseline.concepts.first { IntentType.CONTINUE in it.intentBias }
        val promotedConcepts = baseline.concepts.map { concept ->
            if (concept.id == continueConcept.id) {
                concept.copy(variants = concept.variants + "weiterso")
            } else {
                concept
            }
        }

        val promoted = first.promote(promotedConcepts, "evidence-a").lexicon
        assertNotEquals(baseline.fingerprint, promoted.fingerprint)
        assertEquals(2L, promoted.revision)

        val recoveredRuntime = VersionedLanguageRuntime()
        val recovered = DurableLanguageRuntimeCoordinator(recoveredRuntime, repository).rehydrate()

        assertEquals(promoted.fingerprint, recovered.lexicon.fingerprint)
        assertEquals(
            IntentType.CONTINUE,
            recovered.understanding.understand("weiterso").goal.intent,
        )
        assertEquals(2L, repository.loadHead()!!.headRevision)
        assertEquals(baseline.fingerprint, repository.loadHead()!!.previousActiveSnapshotFingerprint)
    }

    @Test
    fun `rollback is durable head movement without deleting promoted snapshot`() = runTest {
        val repository = InMemoryLanguageRuntimeStateRepository()
        val runtime = VersionedLanguageRuntime()
        val coordinator = DurableLanguageRuntimeCoordinator(runtime, repository)
        val baseline = coordinator.rehydrate().lexicon
        val continueConcept = baseline.concepts.first { IntentType.CONTINUE in it.intentBias }
        val promoted = coordinator.promote(
            concepts = baseline.concepts.map { concept ->
                if (concept.id == continueConcept.id) {
                    concept.copy(variants = concept.variants + "weiterso")
                } else {
                    concept
                }
            },
            promotionEvidenceFingerprint = "evidence-b",
        ).lexicon

        val rolledBack = coordinator.rollback(baseline.fingerprint)

        assertEquals(baseline.fingerprint, rolledBack.lexicon.fingerprint)
        assertEquals(3L, repository.loadHead()!!.headRevision)
        assertEquals(promoted.fingerprint, repository.loadHead()!!.previousActiveSnapshotFingerprint)
        assertEquals(promoted, repository.loadSnapshot(promoted.fingerprint))
    }

    @Test
    fun `state codec round trips snapshot and head exactly`() {
        val snapshot = LinguisticLexiconSnapshot.builtin()
        val decodedSnapshot = LanguageRuntimeStateCodec.decodeSnapshot(
            LanguageRuntimeStateCodec.encodeSnapshot(snapshot)
        )
        assertEquals(snapshot, decodedSnapshot)

        val head = DurableLanguageRuntimeHead(
            headRevision = 1L,
            activeSnapshotFingerprint = snapshot.fingerprint,
            activeLexiconRevision = snapshot.revision,
            previousActiveSnapshotFingerprint = null,
        )
        assertEquals(
            head,
            LanguageRuntimeStateCodec.decodeHead(LanguageRuntimeStateCodec.encodeHead(head)),
        )
    }

    private class InMemoryLanguageRuntimeStateRepository : LanguageRuntimeStateRepository {
        private val snapshots = linkedMapOf<String, LinguisticLexiconSnapshot>()
        private var head: DurableLanguageRuntimeHead? = null

        override suspend fun saveSnapshot(snapshot: LinguisticLexiconSnapshot) {
            val existing = snapshots[snapshot.fingerprint]
            if (existing == null) snapshots[snapshot.fingerprint] = snapshot
            else require(existing == snapshot)
        }

        override suspend fun loadSnapshot(fingerprint: String): LinguisticLexiconSnapshot? =
            snapshots[fingerprint]

        override suspend fun loadHead(): DurableLanguageRuntimeHead? = head

        override suspend fun compareAndSetHead(
            expectedRevision: Long?,
            next: DurableLanguageRuntimeHead,
        ): Boolean {
            if (head?.headRevision != expectedRevision) return false
            require(next.headRevision == (expectedRevision ?: 0L) + 1L)
            require(next.previousActiveSnapshotFingerprint == head?.activeSnapshotFingerprint)
            requireNotNull(snapshots[next.activeSnapshotFingerprint])
            head = next
            return true
        }
    }
}
