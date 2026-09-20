package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalCorpusLanguageRuntimeTest {
    private val now = Instant.parse("2026-09-20T09:00:00Z")

    @Test
    fun `owner corpus can create one-turn alias without mutating productive lexicon`() = runTest {
        val repository = InMemoryRevisionedPhotonRepository()
        val importer = PersonalConversationCorpusImporter(repository)
        importer.import(
            (1..4).map { index ->
                ownerTurn(
                    conversationId = "owner-$index",
                    text = "weiter glorpax",
                    seconds = index.toLong(),
                )
            }
        )

        val versioned = VersionedLanguageRuntime()
        val before = versioned.current().lexicon.fingerprint
        val runtime = PersonalCorpusLanguageRuntime(
            corpus = PersonalCorpusRetriever(repository),
            runtime = versioned,
        )

        val decision = runtime.understand(
            utterance = "glorpax",
            context = LanguageContext(now = now, zoneId = "Europe/Berlin"),
            now = now,
        )

        assertTrue(decision.usedCorpusShadow)
        assertEquals(IntentType.CONTINUE, decision.understanding.goal.intent)
        assertNotEquals(IntentType.CONTINUE, decision.baseline.goal.intent)
        assertEquals(before, decision.baselineLexiconFingerprint)
        assertEquals(before, versioned.current().lexicon.fingerprint)
        assertNotNull(decision.shadowLexiconFingerprint)
        assertNotEquals(before, decision.shadowLexiconFingerprint)

        val source = Photon(
            content = "glorpax",
            provenance = Provenance(
                source = "test",
                actor = "owner",
                createdAt = now,
            ),
            tags = setOf("chat", "chat:user"),
        )
        val evidence = assertNotNull(decision.evidencePhoton(source))
        assertTrue("privacy:local-only" in evidence.tags)
        assertTrue("privacy:no-external-export" in evidence.tags)
        assertTrue("personal-corpus-language-evidence" in evidence.tags)
        assertFalse(evidence.content.contains("weiter glorpax"))
    }

    @Test
    fun `assistant and other corpus turns never create owner language hypothesis`() = runTest {
        val repository = InMemoryRevisionedPhotonRepository()
        val importer = PersonalConversationCorpusImporter(repository)
        importer.import(
            buildList {
                repeat(4) { index ->
                    add(
                        PersonalConversationTurn(
                            source = PersonalConversationSource.GEMINI,
                            conversationId = "assistant-$index",
                            speaker = PersonalConversationSpeaker.ASSISTANT,
                            text = "weiter glorpax",
                            observedAt = now.minusSeconds((index + 1).toLong()),
                            externalMessageId = "assistant-$index",
                        )
                    )
                    add(
                        PersonalConversationTurn(
                            source = PersonalConversationSource.WHATSAPP,
                            conversationId = "other-$index",
                            speaker = PersonalConversationSpeaker.OTHER,
                            text = "weiter glorpax",
                            observedAt = now.minusSeconds((index + 20).toLong()),
                            externalMessageId = "other-$index",
                        )
                    )
                }
            }
        )
        val versioned = VersionedLanguageRuntime()
        val runtime = PersonalCorpusLanguageRuntime(
            corpus = PersonalCorpusRetriever(repository),
            runtime = versioned,
        )

        val decision = runtime.understand(
            utterance = "glorpax",
            context = LanguageContext(now = now),
            now = now,
        )

        assertFalse(decision.usedCorpusShadow)
        assertNull(decision.hypothesis)
    }

    @Test
    fun `conflicting owner intents reject corpus alias hypothesis`() = runTest {
        val repository = InMemoryRevisionedPhotonRepository()
        val importer = PersonalConversationCorpusImporter(repository)
        importer.import(
            listOf(
                ownerTurn("continue-1", "weiter glorpax", 1),
                ownerTurn("continue-2", "weiter glorpax", 2),
                ownerTurn("search-1", "suche glorpax", 3),
                ownerTurn("search-2", "suche glorpax", 4),
            )
        )
        val versioned = VersionedLanguageRuntime()
        val runtime = PersonalCorpusLanguageRuntime(
            corpus = PersonalCorpusRetriever(repository),
            runtime = versioned,
        )

        val decision = runtime.understand(
            utterance = "glorpax",
            context = LanguageContext(now = now),
            now = now,
        )

        assertFalse(decision.usedCorpusShadow)
        assertEquals(versioned.current().lexicon.fingerprint, decision.baselineLexiconFingerprint)
    }

    @Test
    fun `archive photon carries explicit local-only export barriers`() {
        val photon = ownerTurn(
            conversationId = "privacy",
            text = "mein sehr privater text",
            seconds = 1,
        ).toPhoton()

        assertTrue("corpus:archive" in photon.tags)
        assertTrue("privacy:private-conversation" in photon.tags)
        assertTrue("privacy:local-only" in photon.tags)
        assertTrue("privacy:no-external-export" in photon.tags)
        assertTrue("privacy:no-autonomous-share" in photon.tags)
    }

    private fun ownerTurn(
        conversationId: String,
        text: String,
        seconds: Long,
    ): PersonalConversationTurn = PersonalConversationTurn(
        source = PersonalConversationSource.WHATSAPP,
        conversationId = conversationId,
        speaker = PersonalConversationSpeaker.OWNER,
        text = text,
        observedAt = now.minusSeconds(seconds),
        externalMessageId = "$conversationId-$seconds",
    )

    private class InMemoryRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            photons[photon.id] = photon
        }

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(
                photons = photons.values.toList(),
                unreadableFiles = emptyList(),
            )

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            photons[ref.photonId]?.takeIf { it.revision == ref.revision }

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            photons[id]?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val existing = photons[photon.id]
            if (existing == null) {
                if (expectedPreviousRevision != null) {
                    return PhotonRevisionWriteResult.Conflict(
                        photon = photon,
                        previous = null,
                        reason = "missing-previous",
                    )
                }
                photons[photon.id] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (existing == photon) {
                return PhotonRevisionWriteResult.Idempotent(
                    photon = photon,
                    previous = existing,
                )
            }
            return PhotonRevisionWriteResult.Conflict(
                photon = photon,
                previous = existing,
                reason = "immutable-test-repository",
            )
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val filtered = photons.values.asSequence().filter { photon ->
                (query.ids.isEmpty() || photon.id in query.ids) &&
                    (query.phases.isEmpty() || photon.phase in query.phases) &&
                    (query.mimeTypes.isEmpty() || photon.mimeType in query.mimeTypes) &&
                    photon.tags.containsAll(query.allTags) &&
                    photon.tags.none { it in query.excludedTags }
            }
            val sorted = when (query.order) {
                PhotonIndexOrder.NEWEST_FIRST -> filtered.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST -> filtered.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS -> filtered.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE -> filtered.sortedByDescending { it.confidence }
                PhotonIndexOrder.IDENTITY -> filtered.sortedBy { it.id.value }
            }
            return sorted
                .take(query.limit)
                .map { PhotonRevisionRef(it.id, it.revision) }
                .toList()
        }

        override suspend fun indexReport(): PhotonIndexReport =
            PhotonIndexReport(
                formatVersion = 1,
                entryCount = photons.size,
                livePhotonCount = photons.size,
                tombstonedPhotonCount = 0,
                latestRefs = photons.values.associate { photon ->
                    photon.id to PhotonRevisionRef(photon.id, photon.revision)
                },
            )
    }
}
