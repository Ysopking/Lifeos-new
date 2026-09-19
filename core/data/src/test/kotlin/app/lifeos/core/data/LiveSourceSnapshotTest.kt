package app.lifeos.core.data

import app.lifeos.core.model.source.SourcePrivacyZone

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveSourceSnapshotTest {
    private val sourceId = LiveSourceId("snapshot-source")
    private val identity = "a".repeat(64)
    private val at = Instant.parse("2026-09-19T04:00:00Z")

    @Test
    fun diffIsDeterministicAndCoversCreateUpdateDelete() {
        val previous = listOf(
            SourceInventoryItem("deleted", "v1", SourcePrivacyZone.PRIVATE),
            SourceInventoryItem("same", "v1", SourcePrivacyZone.PRIVATE),
            SourceInventoryItem("updated", "v1", SourcePrivacyZone.SENSITIVE),
        )
        val current = listOf(
            SourceInventoryItem("created", "v1", SourcePrivacyZone.SHAREABLE),
            SourceInventoryItem("same", "v1", SourcePrivacyZone.PRIVATE),
            SourceInventoryItem("updated", "v2", SourcePrivacyZone.SENSITIVE),
        )

        val first = LiveSourceSnapshotDiff.between(sourceId, previous, current, 40L)
        val second = LiveSourceSnapshotDiff.between(sourceId, previous.reversed(), current.reversed(), 40L)

        assertEquals(first, second)
        assertEquals(listOf("created", "deleted", "updated"), first.map { it.externalKey })
        assertEquals(
            listOf(SourceDeltaKind.CREATED, SourceDeltaKind.DELETED, SourceDeltaKind.UPDATED),
            first.map { it.kind },
        )
        assertEquals(listOf(41L, 42L, 43L), first.map { it.observationRevision })
    }

    @Test
    fun privacyChangeIsARealUpdateEvenWhenFingerprintIsStable() {
        val deltas = LiveSourceSnapshotDiff.between(
            sourceId = sourceId,
            previous = listOf(SourceInventoryItem("x", "same", SourcePrivacyZone.PRIVATE)),
            current = listOf(SourceInventoryItem("x", "same", SourcePrivacyZone.SHAREABLE)),
            afterObservationRevision = 0L,
        )

        assertEquals(1, deltas.size)
        assertEquals(SourceDeltaKind.UPDATED, deltas.single().kind)
        assertEquals(SourcePrivacyZone.SHAREABLE, deltas.single().privacyZone)
    }

    @Test
    fun snapshotCodecRoundTripsAndRejectsTrailingBytes() {
        val state = LiveSourceSnapshotState.initial(
            sourceId = sourceId,
            connectorIdentityFingerprint = identity,
            items = listOf(
                SourceInventoryItem("b", "fb", SourcePrivacyZone.SENSITIVE),
                SourceInventoryItem("a", "fa", SourcePrivacyZone.PRIVATE),
            ),
            lastObservationRevision = 7L,
            at = at,
        )

        val bytes = LiveSourceSnapshotStateCodec.encode(state)
        assertEquals(state, LiveSourceSnapshotStateCodec.decode(bytes))
        assertFailsWith<IllegalArgumentException> {
            LiveSourceSnapshotStateCodec.decode(bytes + byteArrayOf(1))
        }
    }

    @Test
    fun snapshotRejectsFingerprintThatDoesNotDescribeItsItems() {
        assertFailsWith<IllegalArgumentException> {
            LiveSourceSnapshotState(
                revision = 1L,
                sourceId = sourceId,
                connectorIdentityFingerprint = identity,
                inventoryFingerprint = "b".repeat(64),
                items = emptyList(),
                lastObservationRevision = 0L,
                updatedAt = at,
            )
        }
    }
}
