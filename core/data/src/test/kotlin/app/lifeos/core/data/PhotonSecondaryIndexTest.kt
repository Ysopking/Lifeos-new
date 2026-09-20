package app.lifeos.core.data

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexEntry
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.matches
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class PhotonSecondaryIndexTest {
    @Test
    fun `allTags intersect while anyTags form one union constraint`() {
        val entries = listOf(
            entry("a", tags = setOf("corpus:archive", "speaker:owner", "corpus-term:alpha")),
            entry("b", tags = setOf("corpus:archive", "speaker:owner", "corpus-term:beta")),
            entry("c", tags = setOf("corpus:archive", "speaker:assistant", "corpus-term:alpha")),
            entry("d", tags = setOf("other", "speaker:owner", "corpus-term:alpha")),
        )
        val index = PhotonSecondaryIndex.build(entries)

        val actual = index.query(
            PhotonIndexQuery(
                allTags = setOf("corpus:archive", "speaker:owner"),
                anyTags = setOf("corpus-term:alpha", "corpus-term:beta"),
                order = PhotonIndexOrder.IDENTITY,
            )
        )

        assertEquals(listOf(ref("a"), ref("b")), actual)
    }

    @Test
    fun `secondary index preserves legacy ordering for every order mode`() {
        val entries = listOf(
            entry("b", revision = 1, createdAt = 20, semanticMass = 0.4, confidence = 0.8),
            entry("a", revision = 2, createdAt = 30, semanticMass = 0.9, confidence = 0.5),
            entry("a", revision = 1, createdAt = 10, semanticMass = 0.1, confidence = 0.9, latest = false),
            entry("c", revision = 1, createdAt = 30, semanticMass = 0.9, confidence = 0.5),
        )
        val index = PhotonSecondaryIndex.build(entries)

        PhotonIndexOrder.values().forEach { order ->
            val query = PhotonIndexQuery(
                latestOnly = false,
                includeTombstoned = true,
                order = order,
                limit = 32,
            )
            assertEquals(
                legacyQuery(entries, query),
                index.query(query),
                "order=$order",
            )
        }
    }

    @Test
    fun `cursor semantics remain identical to the legacy query`() {
        val entries = (1..8).map { number ->
            entry(
                id = "p-$number",
                createdAt = number.toLong(),
                tags = setOf("group"),
            )
        }
        val index = PhotonSecondaryIndex.build(entries)
        val firstPage = PhotonIndexQuery(
            allTags = setOf("group"),
            order = PhotonIndexOrder.NEWEST_FIRST,
            limit = 3,
        )
        val first = index.query(firstPage)
        val next = firstPage.copy(
            after = PhotonIndexCursor(
                order = firstPage.order,
                lastRef = first.last(),
            )
        )

        assertEquals(legacyQuery(entries, firstPage), first)
        assertEquals(legacyQuery(entries, next), index.query(next))

        assertFails {
            index.query(
                next.copy(
                    after = PhotonIndexCursor(
                        order = next.order,
                        lastRef = ref("not-present"),
                    )
                )
            )
        }
    }

    @Test
    fun `latest tombstone exclusions and compound constraints remain exact`() {
        val entries = listOf(
            entry(
                "alpha",
                revision = 1,
                phase = PhotonPhase.ARCHIVED,
                mimeType = "text/plain",
                tags = setOf("memory", "private"),
                latest = false,
            ),
            entry(
                "alpha",
                revision = 2,
                phase = PhotonPhase.ACTIVE,
                mimeType = "text/plain",
                tags = setOf("memory", "current"),
            ),
            entry(
                "beta",
                phase = PhotonPhase.ACTIVE,
                mimeType = "application/json",
                tags = setOf("memory", "current"),
                tombstoned = true,
            ),
            entry(
                "gamma",
                phase = PhotonPhase.ACTIVE,
                mimeType = "text/plain",
                tags = setOf("memory", "blocked"),
            ),
        )
        val index = PhotonSecondaryIndex.build(entries)
        val query = PhotonIndexQuery(
            ids = setOf(PhotonId("alpha"), PhotonId("gamma")),
            phases = setOf(PhotonPhase.ACTIVE),
            mimeTypes = setOf("text/plain"),
            allTags = setOf("memory"),
            anyTags = setOf("current", "blocked"),
            excludedTags = setOf("blocked"),
            order = PhotonIndexOrder.IDENTITY,
        )

        assertEquals(listOf(ref("alpha", 2)), index.query(query))
        assertEquals(legacyQuery(entries, query), index.query(query))
    }

    @Test
    fun `250k entry projection remains semantically bounded and deterministic`() {
        val entries = List(250_000) { index ->
            entry(
                id = "bulk-${index.toString().padStart(6, '0')}",
                createdAt = index.toLong(),
                phase = if (index % 2 == 0) PhotonPhase.ACTIVE else PhotonPhase.ARCHIVED,
                tags = setOf(
                    "bulk",
                    "bucket:${index % 32}",
                ),
                semanticMass = (index % 100).toDouble() / 10.0,
                confidence = (index % 101).toDouble() / 100.0,
            )
        }
        val secondary = PhotonSecondaryIndex.build(entries)
        val query = PhotonIndexQuery(
            phases = setOf(PhotonPhase.ACTIVE),
            allTags = setOf("bulk"),
            anyTags = setOf("bucket:7", "bucket:11"),
            order = PhotonIndexOrder.NEWEST_FIRST,
            limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
        )
        val actual = secondary.query(query)

        assertEquals(PhotonIndexQuery.HARD_PAGE_LIMIT, actual.size)
        assertEquals(legacyQuery(entries, query), actual)
        assertTrue(actual.zipWithNext().all { (left, right) ->
            entryIndex(left) > entryIndex(right)
        })
    }

    private fun legacyQuery(
        entries: List<PhotonIndexEntry>,
        query: PhotonIndexQuery,
    ): List<PhotonRevisionRef> {
        val ordering = when (query.order) {
            PhotonIndexOrder.IDENTITY ->
                compareBy<PhotonIndexEntry> { it.ref.photonId.value }
                    .thenBy { it.ref.revision }

            PhotonIndexOrder.NEWEST_FIRST ->
                compareByDescending<PhotonIndexEntry> { it.createdAt }
                    .thenBy { it.ref.photonId.value }
                    .thenByDescending { it.ref.revision }

            PhotonIndexOrder.OLDEST_FIRST ->
                compareBy<PhotonIndexEntry> { it.createdAt }
                    .thenBy { it.ref.photonId.value }
                    .thenBy { it.ref.revision }

            PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                compareByDescending<PhotonIndexEntry> { it.semanticMass }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.ref.photonId.value }

            PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                compareByDescending<PhotonIndexEntry> { it.confidence }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.ref.photonId.value }
        }
        val ordered = entries
            .asSequence()
            .filter { it.matches(query) }
            .sortedWith(ordering)
            .toList()
        val start = query.after?.let { cursor ->
            val position = ordered.indexOfFirst { it.ref == cursor.lastRef }
            require(position >= 0)
            position + 1
        } ?: 0
        return ordered.drop(start).take(query.limit).map { it.ref }
    }

    private fun entry(
        id: String,
        revision: Long = 1,
        createdAt: Long = 1,
        phase: PhotonPhase = PhotonPhase.ACTIVE,
        mimeType: String = "text/plain",
        tags: Set<String> = emptySet(),
        semanticMass: Double = 1.0,
        confidence: Double = 1.0,
        latest: Boolean = true,
        tombstoned: Boolean = false,
    ): PhotonIndexEntry = PhotonIndexEntry(
        ref = ref(id, revision),
        createdAt = Instant.ofEpochSecond(createdAt),
        phase = phase,
        mimeType = mimeType,
        tags = tags,
        semanticMass = semanticMass,
        confidence = confidence,
        contentFingerprint = "0".repeat(64),
        latest = latest,
        tombstoned = tombstoned,
    )

    private fun ref(id: String, revision: Long = 1): PhotonRevisionRef =
        PhotonRevisionRef(PhotonId(id), revision)

    private fun entryIndex(ref: PhotonRevisionRef): Int =
        ref.photonId.value.substringAfterLast('-').toInt()
}
