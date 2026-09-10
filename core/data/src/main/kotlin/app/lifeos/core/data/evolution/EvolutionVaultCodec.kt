package app.lifeos.core.data.evolution

import app.lifeos.core.runtime.evolution.EvolutionCanaryKillSwitchEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcome
import app.lifeos.core.runtime.evolution.EvolutionCanaryPromotionSealEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryReservation
import app.lifeos.core.runtime.evolution.EvolutionCanaryStopReason
import app.lifeos.core.runtime.evolution.EvolutionHardFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/**
 * One logical J09 snapshot. Runtime reservations, stop state, promotion seals and outcomes are
 * encoded together so rehydration cannot observe only part of the evolution safety boundary.
 */
internal data class EvolutionVaultSnapshot(
    val buckets: List<EvolutionVaultBucket> = emptyList(),
) {
    init {
        require(buckets.map { it.adoptionEvidenceId }.distinct().size == buckets.size) {
            "Evolution vault cannot contain duplicate adoption buckets"
        }
    }

    fun bucket(adoptionEvidenceId: String): EvolutionVaultBucket =
        buckets.firstOrNull { it.adoptionEvidenceId == adoptionEvidenceId }
            ?: EvolutionVaultBucket(adoptionEvidenceId = adoptionEvidenceId)

    fun withBucket(bucket: EvolutionVaultBucket): EvolutionVaultSnapshot = copy(
        buckets = (buckets.filterNot { it.adoptionEvidenceId == bucket.adoptionEvidenceId } + bucket)
            .sortedBy { it.adoptionEvidenceId },
    )
}

internal data class EvolutionVaultBucket(
    val adoptionEvidenceId: String,
    val reservations: List<EvolutionCanaryReservation> = emptyList(),
    val killSwitch: EvolutionCanaryKillSwitchEvidence? = null,
    val promotionSeal: EvolutionCanaryPromotionSealEvidence? = null,
    val outcomes: List<EvolutionCanaryOutcome> = emptyList(),
) {
    init {
        require(adoptionEvidenceId.isNotBlank()) { "Evolution vault adoption id must not be blank" }
        require(reservations.all { it.adoptionEvidenceId == adoptionEvidenceId }) {
            "Evolution reservation belongs to another adoption"
        }
        require(reservations.map { it.invocationId }.distinct().size == reservations.size) {
            "Evolution vault cannot contain duplicate reservations"
        }
        require(reservations.map { it.id }.distinct().size == reservations.size) {
            "Evolution vault cannot contain duplicate reservation evidence"
        }
        val orderedReservations = reservations.sortedBy { it.sequence }
        require(orderedReservations.map { it.sequence } == (1..orderedReservations.size).toList()) {
            "Evolution reservation sequence must be contiguous"
        }
        require(killSwitch == null || killSwitch.adoptionEvidenceId == adoptionEvidenceId) {
            "Evolution kill switch belongs to another adoption"
        }
        require(promotionSeal == null || promotionSeal.adoptionEvidenceId == adoptionEvidenceId) {
            "Evolution promotion seal belongs to another adoption"
        }
        require(promotionSeal == null || promotionSeal.expectedReservedInvocations == reservations.size) {
            "Evolution promotion seal reservation count does not match vault"
        }
        require(outcomes.all { it.adoptionEvidenceId == adoptionEvidenceId }) {
            "Evolution outcome belongs to another adoption"
        }
        require(outcomes.map { it.invocationId }.distinct().size == outcomes.size) {
            "Evolution vault cannot contain duplicate outcomes"
        }
        require(outcomes.map { it.id }.distinct().size == outcomes.size) {
            "Evolution vault cannot contain duplicate outcome evidence"
        }
        require(outcomes.size <= reservations.size) {
            "Evolution outcomes cannot exceed reservations"
        }
        val reservationByInvocation = reservations.associateBy { it.invocationId }
        outcomes.forEach { outcome ->
            val reservation = requireNotNull(reservationByInvocation[outcome.invocationId]) {
                "Evolution outcome has no matching reservation"
            }
            require(reservation.id == outcome.reservationId) {
                "Evolution outcome reservation evidence does not match vault"
            }
        }
        val candidateIds = buildSet {
            killSwitch?.candidateToolId?.let(::add)
            promotionSeal?.candidateToolId?.let(::add)
            outcomes.mapTo(this) { it.candidateToolId }
        }
        require(candidateIds.size <= 1) {
            "Evolution adoption bucket cannot mix candidate tool identities"
        }

        val hardFailureOutcomes = outcomes.filter { it.hardFailures.isNotEmpty() }
        if (hardFailureOutcomes.isNotEmpty()) {
            require(hardFailureOutcomes.size == 1) {
                "Evolution vault cannot contain outcomes after the first hard failure"
            }
            val hardFailure = hardFailureOutcomes.single()
            require(killSwitch != null) {
                "Persisted hard failure requires an atomic kill switch"
            }
            require(killSwitch.reason == EvolutionCanaryStopReason.HARD_FAILURE) {
                "Persisted hard failure requires HARD_FAILURE stop reason"
            }
            require(killSwitch.triggerOutcomeId == hardFailure.id) {
                "Hard-failure kill switch must reference the persisted failing outcome"
            }
            require(killSwitch.hardFailures == hardFailure.hardFailures) {
                "Hard-failure kill switch evidence differs from failing outcome"
            }
        }
    }
}

/** Strict binary codec kept Android-free so corruption and rehydration invariants are JVM-tested. */
internal object EvolutionVaultCodec {
    const val VERSION = 1
    private const val MAGIC = 0x4C45564F // LEVO
    private const val MAX_BUCKETS = 512
    private const val MAX_ENTRIES_PER_BUCKET = 10_000
    private const val MAX_STRING_BYTES = 16 * 1024
    private const val MAX_FAILURES = 32

    fun encode(snapshot: EvolutionVaultSnapshot): ByteArray {
        require(snapshot.buckets.size <= MAX_BUCKETS) { "Too many evolution vault buckets" }
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                val buckets = snapshot.buckets.sortedBy { it.adoptionEvidenceId }
                data.writeInt(buckets.size)
                buckets.forEach { data.writeBucket(it) }
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): EvolutionVaultSnapshot {
        require(bytes.isNotEmpty()) { "Evolution vault payload is empty" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid evolution vault magic" }
            require(data.readInt() == VERSION) { "Unsupported evolution vault version" }
            val bucketCount = data.readBoundedCount(MAX_BUCKETS, "adoption buckets")
            val buckets = List(bucketCount) { data.readBucket() }
            require(data.read() == -1) { "Evolution vault contains trailing bytes" }
            EvolutionVaultSnapshot(buckets)
        }
    }

    private fun DataOutputStream.writeBucket(bucket: EvolutionVaultBucket) {
        writeText(bucket.adoptionEvidenceId)
        require(bucket.reservations.size <= MAX_ENTRIES_PER_BUCKET)
        val reservations = bucket.reservations.sortedBy { it.sequence }
        writeInt(reservations.size)
        reservations.forEach(::writeReservation)

        writeBoolean(bucket.killSwitch != null)
        bucket.killSwitch?.let(::writeKillSwitch)

        writeBoolean(bucket.promotionSeal != null)
        bucket.promotionSeal?.let(::writePromotionSeal)

        require(bucket.outcomes.size <= MAX_ENTRIES_PER_BUCKET)
        val outcomes = bucket.outcomes.sortedBy { it.invocationId }
        writeInt(outcomes.size)
        outcomes.forEach(::writeOutcome)
    }

    private fun DataInputStream.readBucket(): EvolutionVaultBucket {
        val adoptionEvidenceId = readText()
        val reservations = List(readBoundedCount(MAX_ENTRIES_PER_BUCKET, "reservations")) {
            readReservation().also { require(it.adoptionEvidenceId == adoptionEvidenceId) }
        }
        val killSwitch = if (readBoolean()) readKillSwitch() else null
        val promotionSeal = if (readBoolean()) readPromotionSeal() else null
        val outcomes = List(readBoundedCount(MAX_ENTRIES_PER_BUCKET, "outcomes")) { readOutcome() }
        return EvolutionVaultBucket(
            adoptionEvidenceId = adoptionEvidenceId,
            reservations = reservations,
            killSwitch = killSwitch,
            promotionSeal = promotionSeal,
            outcomes = outcomes,
        )
    }

    private fun DataOutputStream.writeReservation(value: EvolutionCanaryReservation) {
        writeText(value.id)
        writeText(value.adoptionEvidenceId)
        writeText(value.invocationId)
        writeInt(value.sequence)
        writeInstant(value.reservedAt)
    }

    private fun DataInputStream.readReservation(): EvolutionCanaryReservation {
        val expectedId = readText()
        val value = EvolutionCanaryReservation(
            adoptionEvidenceId = readText(),
            invocationId = readText(),
            sequence = readInt(),
            reservedAt = readInstant(),
        )
        require(value.id == expectedId) { "Evolution reservation id integrity check failed" }
        return value
    }

    private fun DataOutputStream.writeKillSwitch(value: EvolutionCanaryKillSwitchEvidence) {
        writeText(value.id)
        writeText(value.adoptionEvidenceId)
        writeText(value.candidateToolId)
        writeText(value.reason.name)
        writeText(value.triggerOutcomeId)
        writeFailures(value.hardFailures)
        writeInstant(value.trippedAt)
    }

    private fun DataInputStream.readKillSwitch(): EvolutionCanaryKillSwitchEvidence {
        val expectedId = readText()
        val value = EvolutionCanaryKillSwitchEvidence(
            adoptionEvidenceId = readText(),
            candidateToolId = readText(),
            reason = enumValueOf<EvolutionCanaryStopReason>(readText()),
            triggerOutcomeId = readText(),
            hardFailures = readFailures(),
            trippedAt = readInstant(),
        )
        require(value.id == expectedId) { "Evolution kill-switch id integrity check failed" }
        return value
    }

    private fun DataOutputStream.writePromotionSeal(value: EvolutionCanaryPromotionSealEvidence) {
        writeText(value.id)
        writeText(value.adoptionEvidenceId)
        writeText(value.candidateToolId)
        writeText(value.readinessEvidenceId)
        writeInt(value.expectedReservedInvocations)
        writeInstant(value.sealedAt)
    }

    private fun DataInputStream.readPromotionSeal(): EvolutionCanaryPromotionSealEvidence {
        val expectedId = readText()
        val value = EvolutionCanaryPromotionSealEvidence(
            adoptionEvidenceId = readText(),
            candidateToolId = readText(),
            readinessEvidenceId = readText(),
            expectedReservedInvocations = readInt(),
            sealedAt = readInstant(),
        )
        require(value.id == expectedId) { "Evolution promotion-seal id integrity check failed" }
        return value
    }

    private fun DataOutputStream.writeOutcome(value: EvolutionCanaryOutcome) {
        writeText(value.id)
        writeText(value.adoptionEvidenceId)
        writeText(value.reservationId)
        writeText(value.candidateToolId)
        writeText(value.candidateRecordFingerprint)
        writeText(value.invocationId)
        writeText(value.inputFingerprint)
        writeBoolean(value.success)
        writeBoolean(value.producedExpectedOutput)
        writeNullableText(value.outputFingerprint)
        writeLong(value.latencyMs)
        writeFailures(value.hardFailures)
        writeInstant(value.recordedAt)
    }

    private fun DataInputStream.readOutcome(): EvolutionCanaryOutcome {
        val expectedId = readText()
        val value = EvolutionCanaryOutcome(
            adoptionEvidenceId = readText(),
            reservationId = readText(),
            candidateToolId = readText(),
            candidateRecordFingerprint = readText(),
            invocationId = readText(),
            inputFingerprint = readText(),
            success = readBoolean(),
            producedExpectedOutput = readBoolean(),
            outputFingerprint = readNullableText(),
            latencyMs = readLong(),
            hardFailures = readFailures(),
            recordedAt = readInstant(),
        )
        require(value.id == expectedId) { "Evolution outcome id integrity check failed" }
        return value
    }

    private fun DataOutputStream.writeFailures(values: Set<EvolutionHardFailure>) {
        require(values.size <= MAX_FAILURES)
        val ordered = values.sortedBy { it.name }
        writeInt(ordered.size)
        ordered.forEach { writeText(it.name) }
    }

    private fun DataInputStream.readFailures(): Set<EvolutionHardFailure> = buildSet {
        repeat(readBoundedCount(MAX_FAILURES, "hard failures")) {
            require(add(enumValueOf<EvolutionHardFailure>(readText()))) {
                "Evolution vault contains duplicate hard failure"
            }
        }
    }

    private fun DataOutputStream.writeInstant(value: Instant) = writeText(value.toString())

    private fun DataInputStream.readInstant(): Instant = Instant.parse(readText())

    private fun DataOutputStream.writeNullableText(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeText(value)
    }

    private fun DataInputStream.readNullableText(): String? = if (readBoolean()) readText() else null

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Evolution vault string exceeds size limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid evolution vault string length" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataInputStream.readBoundedCount(maximum: Int, label: String): Int = readInt().also {
        require(it in 0..maximum) { "Invalid evolution vault $label count: $it" }
    }
}
