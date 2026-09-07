package app.lifeos.core.runtime.checkpoints

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.ForceField
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FieldProgressCheckpointTest {
    @Test
    fun roundTripPreservesSignatureAndCompletedFields() {
        val progress = FieldProgressCheckpoint(
            fieldSignature = "abc123",
            completedFieldIndexes = linkedSetOf(0, 2, 4),
        )

        val decoded = FieldProgressCheckpointCodec.decode(
            FieldProgressCheckpointCodec.encode(progress)
        )

        assertEquals(progress, decoded)
    }

    @Test
    fun signatureIsStableForSameFieldOrderAndChangesWhenOrderChanges() {
        val first = FirstField()
        val second = SecondField()

        val original = FieldProgressCheckpointCodec.signature(listOf(first, second))
        val repeated = FieldProgressCheckpointCodec.signature(listOf(first, second))
        val reversed = FieldProgressCheckpointCodec.signature(listOf(second, first))

        assertEquals(original, repeated)
        assertNotEquals(original, reversed)
    }

    @Test
    fun trailingDataIsRejected() {
        val encoded = FieldProgressCheckpointCodec.encode(
            FieldProgressCheckpoint("signature", setOf(1))
        )
        val malformed = encoded + byteArrayOf(1)

        assertFailsWith<IllegalArgumentException> {
            FieldProgressCheckpointCodec.decode(malformed)
        }
    }

    @Test
    fun encodingIsDeterministicRegardlessOfSetIterationOrder() {
        val first = FieldProgressCheckpointCodec.encode(
            FieldProgressCheckpoint("signature", linkedSetOf(3, 1, 2))
        )
        val second = FieldProgressCheckpointCodec.encode(
            FieldProgressCheckpoint("signature", linkedSetOf(1, 2, 3))
        )

        assertContentEquals(first, second)
    }

    private class FirstField : ForceField {
        override suspend fun influence(photon: Photon) = null
    }

    private class SecondField : ForceField {
        override suspend fun influence(photon: Photon) = null
    }
}
