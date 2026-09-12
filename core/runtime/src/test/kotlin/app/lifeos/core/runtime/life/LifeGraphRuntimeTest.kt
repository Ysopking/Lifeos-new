package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LifeGraphRuntimeTest {
    @Test
    fun explicitEntitiesAndTimelineAreRebuiltDeterministically() {
        val photon = Photon(
            id = PhotonId("mail-1"),
            content = "Projekt Alpha mit Beispiel GmbH",
            provenance = Provenance("mail", "user", Instant.parse("2026-09-01T10:00:00Z")),
            tags = setOf("project:alpha", "organization:beispiel-gmbh"),
        )
        val projector = LifeGraphProjector()

        val first = projector.project(listOf(photon))
        val second = projector.project(listOf(photon))

        assertEquals(first, second)
        assertEquals(2, first.entities.size)
        assertEquals(1, first.events.size)
        assertEquals(1, first.relationships.size)
    }

    @Test
    fun ingestDeduplicatesSourceRecordsAndAdvancesCursor() {
        val cursor = LifeSourceCursor("mail", "10")
        val record = LifeSourceRecord(
            sourceId = "mail",
            recordId = "11",
            observedAt = Instant.EPOCH,
            payload = "hello",
        )
        val result = ContinuousLifeIngestEngine().ingest(cursor, listOf(record, record), "11", authorized = true)

        assertEquals(1, result.perception.photons.size)
        assertEquals("11", result.cursorAfter.position)
        assertTrue("source-record:11" in result.perception.photons.single().tags)
    }

    @Test
    fun unauthorizedSourceIsRejectedBeforePerception() {
        assertFailsWith<IllegalArgumentException> {
            ContinuousLifeIngestEngine().ingest(
                LifeSourceCursor("private-source", null),
                emptyList(),
                null,
                authorized = false,
            )
        }
    }
}
