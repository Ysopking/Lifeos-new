package app.lifeos.core.data.evolution

import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryOutcome
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReservation
import app.lifeos.core.runtime.evolution.NovelCapabilityPromotionSealEvidence
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NovelPromotionSealVaultTest {
    private val t0 = Instant.parse("2026-09-11T04:30:00Z")

    @Test
    fun `v3 round trip preserves exact novel promotion seal deterministically`() {
        val reservation = reservation("admission-v3", "call-1", 1)
        val outcome = outcome(reservation)
        val seal = NovelCapabilityPromotionSealEvidence(
            admissionEvidenceId = reservation.admissionEvidenceId,
            subjectId = "subject-v3",
            toolId = reservation.toolId,
            candidateRecordFingerprint = reservation.candidateRecordFingerprint,
            artifactId = "artifact-v3",
            readinessEvidenceId = "readiness-v3",
            expectedReservedInvocations = 1,
            sealedAt = t0.plusSeconds(2),
        )
        val snapshot = EvolutionVaultSnapshot(
            novelBuckets = listOf(
                NovelCapabilityVaultBucket(
                    admissionEvidenceId = reservation.admissionEvidenceId,
                    reservations = listOf(reservation),
                    promotionSeal = seal,
                    outcomes = listOf(outcome),
                )
            )
        )

        val encoded = EvolutionVaultCodec.encode(snapshot)
        val decoded = EvolutionVaultCodec.decode(encoded, expectedVersion = 3)

        assertEquals(snapshot, decoded)
        assertEquals(seal, decoded.novelBuckets.single().promotionSeal)
        assertContentEquals(encoded, EvolutionVaultCodec.encode(decoded))
    }

    @Test
    fun `legacy v2 novel bucket remains readable without promotion seal`() {
        val decoded = EvolutionVaultCodec.decode(legacyV2EmptyNovelBucket("legacy-v2"), expectedVersion = 2)

        assertEquals(1, decoded.novelBuckets.size)
        assertEquals("legacy-v2", decoded.novelBuckets.single().admissionEvidenceId)
        assertNull(decoded.novelBuckets.single().promotionSeal)
        assertEquals(emptyList(), decoded.novelBuckets.single().reservations)
        assertEquals(emptyList(), decoded.novelBuckets.single().outcomes)
    }

    @Test
    fun `tampered novel promotion seal id is rejected`() {
        val reservation = reservation("admission-tamper", "call-1", 1)
        val seal = NovelCapabilityPromotionSealEvidence(
            admissionEvidenceId = reservation.admissionEvidenceId,
            subjectId = "subject-tamper",
            toolId = reservation.toolId,
            candidateRecordFingerprint = reservation.candidateRecordFingerprint,
            artifactId = "artifact-tamper",
            readinessEvidenceId = "readiness-tamper",
            expectedReservedInvocations = 1,
            sealedAt = t0.plusSeconds(2),
        )
        val encoded = EvolutionVaultCodec.encode(
            EvolutionVaultSnapshot(
                novelBuckets = listOf(
                    NovelCapabilityVaultBucket(
                        admissionEvidenceId = reservation.admissionEvidenceId,
                        reservations = listOf(reservation),
                        promotionSeal = seal,
                        outcomes = listOf(outcome(reservation)),
                    )
                )
            )
        )
        val idBytes = seal.id.toByteArray(Charsets.UTF_8)
        val offset = encoded.indexOfSubsequence(idBytes)
        require(offset >= 0)
        encoded[offset] = if (encoded[offset] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()

        assertFailsWith<IllegalArgumentException> { EvolutionVaultCodec.decode(encoded) }
    }

    private fun reservation(admission: String, invocation: String, sequence: Int) =
        NovelCapabilityCanaryReservation(
            admissionEvidenceId = admission,
            toolId = "tool-v3",
            candidateRecordFingerprint = "record-v3",
            invocationId = invocation,
            inputFingerprint = "input-$invocation",
            expectedOutputFingerprint = "expected-$invocation",
            sequence = sequence,
            reservedAt = t0,
        )

    private fun outcome(reservation: NovelCapabilityCanaryReservation) =
        NovelCapabilityCanaryOutcome(
            admissionEvidenceId = reservation.admissionEvidenceId,
            reservationId = reservation.id,
            toolId = reservation.toolId,
            candidateRecordFingerprint = reservation.candidateRecordFingerprint,
            invocationId = reservation.invocationId,
            trialResultFingerprint = "trial-${reservation.invocationId}",
            success = true,
            producedExpectedOutput = true,
            safetyViolation = false,
            latencyMs = 5,
            recordedAt = t0.plusSeconds(1),
        )

    private fun legacyV2EmptyNovelBucket(admissionEvidenceId: String): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4C45564F)
                data.writeInt(2)
                data.writeInt(0)
                data.writeInt(1)
                data.writeText(admissionEvidenceId)
                data.writeInt(0)
                data.writeBoolean(false)
                data.writeInt(0)
            }
        }.toByteArray()

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }
}
