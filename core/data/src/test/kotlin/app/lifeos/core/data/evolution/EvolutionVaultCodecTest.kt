package app.lifeos.core.data.evolution

import app.lifeos.core.runtime.evolution.EvolutionCanaryKillSwitchEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcome
import app.lifeos.core.runtime.evolution.EvolutionCanaryPromotionSealEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryReservation
import app.lifeos.core.runtime.evolution.EvolutionCanaryStopReason
import app.lifeos.core.runtime.evolution.EvolutionHardFailure
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EvolutionVaultCodecTest {
    private val t0 = Instant.parse("2026-09-10T16:00:00Z")

    @Test
    fun `round trip preserves sealed and stopped adoption state`() {
        val sealedReservations = listOf(
            reservation("adoption-sealed", "call-1", 1, t0),
            reservation("adoption-sealed", "call-2", 2, t0.plusSeconds(1)),
        )
        val sealedOutcomes = sealedReservations.mapIndexed { index, reservation ->
            cleanOutcome(
                adoptionEvidenceId = "adoption-sealed",
                candidateToolId = "tool-sealed",
                reservation = reservation,
                recordedAt = t0.plusSeconds(10L + index),
            )
        }
        val seal = EvolutionCanaryPromotionSealEvidence(
            adoptionEvidenceId = "adoption-sealed",
            candidateToolId = "tool-sealed",
            readinessEvidenceId = "readiness-sealed",
            expectedReservedInvocations = sealedReservations.size,
            sealedAt = t0.plusSeconds(20),
        )

        val stoppedReservation = reservation("adoption-stopped", "call-stop", 1, t0.plusSeconds(30))
        val stoppedOutcome = hardFailureOutcome(
            adoptionEvidenceId = "adoption-stopped",
            candidateToolId = "tool-stopped",
            reservation = stoppedReservation,
            recordedAt = t0.plusSeconds(31),
        )
        val stop = EvolutionCanaryKillSwitchEvidence(
            adoptionEvidenceId = "adoption-stopped",
            candidateToolId = "tool-stopped",
            reason = EvolutionCanaryStopReason.HARD_FAILURE,
            triggerOutcomeId = stoppedOutcome.id,
            hardFailures = stoppedOutcome.hardFailures,
            trippedAt = t0.plusSeconds(32),
        )

        val snapshot = EvolutionVaultSnapshot(
            listOf(
                EvolutionVaultBucket(
                    adoptionEvidenceId = "adoption-stopped",
                    reservations = listOf(stoppedReservation),
                    killSwitch = stop,
                    outcomes = listOf(stoppedOutcome),
                ),
                EvolutionVaultBucket(
                    adoptionEvidenceId = "adoption-sealed",
                    reservations = sealedReservations,
                    promotionSeal = seal,
                    outcomes = sealedOutcomes,
                ),
            )
        )

        val encoded = EvolutionVaultCodec.encode(snapshot)
        val decoded = EvolutionVaultCodec.decode(encoded)

        assertEquals(snapshot.buckets.sortedBy { it.adoptionEvidenceId }, decoded.buckets)
        assertContentEquals(encoded, EvolutionVaultCodec.encode(decoded))
    }

    @Test
    fun `hard failure outcome without matching stop is rejected`() {
        val reservation = reservation("adoption-hard", "call-hard", 1, t0)
        val outcome = hardFailureOutcome(
            adoptionEvidenceId = "adoption-hard",
            candidateToolId = "tool-hard",
            reservation = reservation,
            recordedAt = t0.plusSeconds(1),
        )

        assertFailsWith<IllegalArgumentException> {
            EvolutionVaultBucket(
                adoptionEvidenceId = "adoption-hard",
                reservations = listOf(reservation),
                outcomes = listOf(outcome),
            )
        }
    }

    @Test
    fun `stored content addressed id mismatch is rejected`() {
        val reservation = reservation("adoption-id", "call-id", 1, t0)
        val snapshot = EvolutionVaultSnapshot(
            listOf(EvolutionVaultBucket("adoption-id", reservations = listOf(reservation)))
        )
        val encoded = EvolutionVaultCodec.encode(snapshot)
        val idBytes = reservation.id.toByteArray(Charsets.UTF_8)
        val offset = encoded.indexOfSubsequence(idBytes)
        require(offset >= 0)
        encoded[offset] = if (encoded[offset] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()

        assertFailsWith<IllegalArgumentException> {
            EvolutionVaultCodec.decode(encoded)
        }
    }

    @Test
    fun `trailing bytes are rejected instead of ignored`() {
        val encoded = EvolutionVaultCodec.encode(
            EvolutionVaultSnapshot(listOf(EvolutionVaultBucket("adoption-trailing")))
        )

        assertFailsWith<IllegalArgumentException> {
            EvolutionVaultCodec.decode(encoded + byteArrayOf(0x01))
        }
    }

    @Test
    fun `outcome without matching reservation is rejected`() {
        val foreignReservation = reservation("adoption-foreign", "call-1", 1, t0)
        val outcome = cleanOutcome(
            adoptionEvidenceId = "adoption-local",
            candidateToolId = "tool-local",
            reservation = foreignReservation.copy(adoptionEvidenceId = "adoption-local"),
            recordedAt = t0.plusSeconds(1),
        )

        assertFailsWith<IllegalArgumentException> {
            EvolutionVaultBucket(
                adoptionEvidenceId = "adoption-local",
                reservations = emptyList(),
                outcomes = listOf(outcome),
            )
        }
    }

    @Test
    fun `reservation sequence gaps and duplicate invocation ids are rejected`() {
        val first = reservation("adoption-sequence", "call-1", 1, t0)
        val gap = reservation("adoption-sequence", "call-2", 3, t0.plusSeconds(1))
        assertFailsWith<IllegalArgumentException> {
            EvolutionVaultBucket(
                adoptionEvidenceId = "adoption-sequence",
                reservations = listOf(first, gap),
            )
        }

        val duplicateInvocation = reservation("adoption-sequence", "call-1", 2, t0.plusSeconds(2))
        assertFailsWith<IllegalArgumentException> {
            EvolutionVaultBucket(
                adoptionEvidenceId = "adoption-sequence",
                reservations = listOf(first, duplicateInvocation),
            )
        }
    }

    private fun reservation(
        adoptionEvidenceId: String,
        invocationId: String,
        sequence: Int,
        reservedAt: Instant,
    ) = EvolutionCanaryReservation(
        adoptionEvidenceId = adoptionEvidenceId,
        invocationId = invocationId,
        sequence = sequence,
        reservedAt = reservedAt,
    )

    private fun cleanOutcome(
        adoptionEvidenceId: String,
        candidateToolId: String,
        reservation: EvolutionCanaryReservation,
        recordedAt: Instant,
    ) = EvolutionCanaryOutcome(
        adoptionEvidenceId = adoptionEvidenceId,
        reservationId = reservation.id,
        candidateToolId = candidateToolId,
        candidateRecordFingerprint = "candidate-record-$candidateToolId",
        invocationId = reservation.invocationId,
        inputFingerprint = "input-${reservation.invocationId}",
        success = true,
        producedExpectedOutput = true,
        outputFingerprint = "output-${reservation.invocationId}",
        latencyMs = 5,
        hardFailures = emptySet(),
        recordedAt = recordedAt,
    )

    private fun hardFailureOutcome(
        adoptionEvidenceId: String,
        candidateToolId: String,
        reservation: EvolutionCanaryReservation,
        recordedAt: Instant,
    ) = EvolutionCanaryOutcome(
        adoptionEvidenceId = adoptionEvidenceId,
        reservationId = reservation.id,
        candidateToolId = candidateToolId,
        candidateRecordFingerprint = "candidate-record-$candidateToolId",
        invocationId = reservation.invocationId,
        inputFingerprint = "input-${reservation.invocationId}",
        success = false,
        producedExpectedOutput = false,
        outputFingerprint = null,
        latencyMs = 9,
        hardFailures = setOf(EvolutionHardFailure.SAFETY_VIOLATION),
        recordedAt = recordedAt,
    )

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
