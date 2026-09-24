package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldFormulaContextRetrievalV2Test {
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun targetedStateDimensionRetrievalFindsOtherwiseLowRankedPhoton() = runTest {
        val target = photon(
            id = "target-state",
            createdAt = now.minusSeconds(86_400),
            tags = setOf(
                "state-dimension:finance.balance",
                "semantic:finance",
            ),
            content = "opaque state payload",
            semanticMass = 0.01,
            confidence = 0.2,
        )
        val distractors = (0 until 220).map { index ->
            photon(
                id = "distractor-$index",
                createdAt = now.minusSeconds(index.toLong()),
                tags = emptySet(),
                content = "unrelated recent $index",
                semanticMass = 8.0,
                confidence = 1.0,
            )
        }
        val repository = FakeRepository(distractors + target)

        val result = LanguageContextRetriever(repository).retrieve(
            utterance = "Wie viel kann ich ausgeben?",
            now = now,
            needs = LanguageContextRetrievalNeeds(
                stateDimensionKeys = setOf("finance.balance"),
            ),
        )

        assertTrue(
            result.context.items.any { it.photonId == target.id }
        )
        assertTrue(
            result.trace.queryFingerprints.any {
                it.startsWith("state:finance.balance:")
            }
        )
        assertEquals(1, result.trace.needsFingerprint?.let { 1 })
    }

    @Test
    fun exactRevisionNeedIsReservedBeforeBroadRanking() = runTest {
        val required = photon(
            id = "exact-target",
            createdAt = now.minusSeconds(90_000),
            tags = setOf("file"),
            content = "old exact revision",
            semanticMass = 0.0,
            confidence = 0.1,
        )
        val distractors = (0 until 180).map { index ->
            photon(
                id = "high-$index",
                createdAt = now.minusSeconds(index.toLong()),
                tags = setOf("result"),
                content = "high ranking $index",
                semanticMass = 8.0,
                confidence = 1.0,
            )
        }
        val repository = FakeRepository(distractors + required)
        val ref = PhotonRevisionRef(required.id, required.revision)

        val result = LanguageContextRetriever(
            photons = repository,
            maxCandidates = 160,
            maxSelected = 16,
        ).retrieve(
            utterance = "das",
            now = now,
            needs = LanguageContextRetrievalNeeds(
                exactRevisionRefs = setOf(ref),
            ),
        )

        assertTrue(result.context.items.any { it.revisionRef == ref })
        assertEquals(1, result.trace.exactRequestedRefs)
    }

    @Test
    fun photonContextBuilderPreservesStateEpisodeAndRealizationTags() {
        val p = photon(
            id = "rich-context",
            createdAt = now,
            tags = setOf(
                "state-dimension:calendar.current-events",
                "temporal-episode:abc",
                "representation:projected",
                "epistemic:observed",
                "temporal:current",
            ),
            content = "calendar state",
        )

        val item = PhotonLanguageContextBuilder()
            .build(listOf(p), now)
            .items
            .single()

        assertEquals(setOf("calendar.current-events"), item.stateDimensionKeys)
        assertEquals(setOf("temporal-episode:abc"), item.episodeRefs)
        assertEquals(
            setOf(
                "representation:projected",
                "epistemic:observed",
                "temporal:current",
            ),
            item.realizationKeys,
        )
    }

    @Test
    fun needPlannerCarriesExactGroundingAndRuntimeSuppliedStateNeeds() {
        val ref = PhotonRevisionRef(PhotonId("grounded"), 4L)
        val grounding = LanguageReferenceGroundingState(
            listOf(
                LanguageReferenceGrounding(
                    expression = ReferenceExpression(
                        kind = ReferenceKind.THAT,
                        rawText = "das",
                        confidence = 0.9,
                    ),
                    selectedPhotonId = ref.photonId,
                    selectedRevisionRef = ref,
                    status = LanguageReferenceGroundingStatus.EXACT_REVISION,
                    score = 0.9,
                    runnerUpScore = null,
                    matchedContextFingerprint = "a".repeat(64),
                )
            )
        )
        val goal = GoalFrame(
            intent = IntentType.QUERY,
            objective = "query: test",
            entities = emptyList(),
            references = emptyList(),
            constraints = emptyList(),
            ambiguities = emptyList(),
            confidence = 1.0,
            language = LanguageCode.DE,
            referenceGrounding = grounding,
        )

        val needs = WorldFormulaContextNeedPlanner().plan(
            goal = goal,
            requiredStateDimensionKeys = setOf("finance.balance"),
            episodeRefs = setOf("temporal-episode:abc"),
        )

        assertEquals(setOf(ref), needs.exactRevisionRefs)
        assertEquals(setOf("finance.balance"), needs.stateDimensionKeys)
        assertEquals(setOf("temporal-episode:abc"), needs.episodeRefs)
    }

    private fun photon(
        id: String,
        createdAt: Instant,
        tags: Set<String>,
        content: String,
        semanticMass: Double = 1.0,
        confidence: Double = 1.0,
    ) = Photon(
        id = PhotonId(id),
        revision = 1L,
        content = content,
        phase = PhotonPhase.ACTIVE,
        semanticMass = semanticMass,
        energy = 0.0,
        confidence = confidence,
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = createdAt,
        ),
        tags = tags,
    )

    private class FakeRepository(
        photons: List<Photon>,
    ) : RevisionedPhotonRepository {
        private val byRef = photons
            .associateBy { PhotonRevisionRef(it.id, it.revision) }
            .toMutableMap()

        override suspend fun save(photon: Photon) {
            byRef[PhotonRevisionRef(photon.id, photon.revision)] = photon
        }

        override suspend fun load(id: PhotonId): Photon? =
            byRef.values.filter { it.id == id }.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? = byRef[ref]

        override suspend fun loadAll(): List<Photon> =
            error("B475 retrieval must remain index-bounded")

        override suspend fun delete(id: PhotonId) {
            byRef.keys.filter { it.photonId == id }.forEach(byRef::remove)
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(byRef.values.toList(), emptyList())

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            byRef.keys.filter { it.photonId == id }.maxByOrNull { it.revision }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previous = load(photon.id)
            byRef[PhotonRevisionRef(photon.id, photon.revision)] = photon
            return if (previous == null) {
                PhotonRevisionWriteResult.Created(photon)
            } else {
                PhotonRevisionWriteResult.Advanced(photon, previous)
            }
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val latest = byRef.values
                .groupBy { it.id }
                .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
                .values
                .asSequence()
                .filter { query.ids.isEmpty() || it.id in query.ids }
                .filter { query.phases.isEmpty() || it.phase in query.phases }
                .filter { query.mimeTypes.isEmpty() || it.mimeType in query.mimeTypes }
                .filter { it.tags.containsAll(query.allTags) }
            val ordered = when (query.order) {
                PhotonIndexOrder.IDENTITY ->
                    latest.sortedWith(compareBy<Photon> { it.id.value }.thenBy { it.revision })
                PhotonIndexOrder.NEWEST_FIRST ->
                    latest.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST ->
                    latest.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                    latest.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                    latest.sortedByDescending { it.confidence }
            }
            return ordered
                .take(query.limit)
                .map { PhotonRevisionRef(it.id, it.revision) }
                .toList()
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = byRef.keys
                .groupBy { it.photonId }
                .mapValues { (_, refs) -> refs.maxBy { it.revision } }
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = byRef.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest,
            )
        }
    }
}
