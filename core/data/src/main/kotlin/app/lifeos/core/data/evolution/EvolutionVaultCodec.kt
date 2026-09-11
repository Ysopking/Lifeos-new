package app.lifeos.core.data.evolution

import app.lifeos.core.runtime.evolution.EvolutionCanaryKillSwitchEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcome
import app.lifeos.core.runtime.evolution.EvolutionCanaryPromotionSealEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryReservation
import app.lifeos.core.runtime.evolution.EvolutionCanaryStopReason
import app.lifeos.core.runtime.evolution.EvolutionHardFailure
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryKillSwitchEvidence
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryOutcome
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReservation
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryStopReason
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/**
 * One logical evolution snapshot. Legacy replacement-evolution buckets and novel-capability canary
 * buckets share the same encrypted/atomic vault while retaining distinct evidence semantics.
 */
internal data class EvolutionVaultSnapshot(
    val buckets: List<EvolutionVaultBucket> = emptyList(),
    val novelBuckets: List<NovelCapabilityVaultBucket> = emptyList(),
) {
    init {
        require(buckets.map { it.adoptionEvidenceId }.distinct().size == buckets.size) {
            "Evolution vault cannot contain duplicate adoption buckets"
        }
        require(novelBuckets.map { it.admissionEvidenceId }.distinct().size == novelBuckets.size) {
            "Evolution vault cannot contain duplicate novel admission buckets"
        }
    }

    fun bucket(adoptionEvidenceId: String): EvolutionVaultBucket =
        buckets.firstOrNull { it.adoptionEvidenceId == adoptionEvidenceId }
            ?: EvolutionVaultBucket(adoptionEvidenceId = adoptionEvidenceId)

    fun withBucket(bucket: EvolutionVaultBucket): EvolutionVaultSnapshot = copy(
        buckets = (buckets.filterNot { it.adoptionEvidenceId == bucket.adoptionEvidenceId } + bucket)
            .sortedBy { it.adoptionEvidenceId },
    )

    fun novelBucket(admissionEvidenceId: String): NovelCapabilityVaultBucket =
        novelBuckets.firstOrNull { it.admissionEvidenceId == admissionEvidenceId }
            ?: NovelCapabilityVaultBucket(admissionEvidenceId = admissionEvidenceId)

    fun withNovelBucket(bucket: NovelCapabilityVaultBucket): EvolutionVaultSnapshot = copy(
        novelBuckets = (novelBuckets.filterNot { it.admissionEvidenceId == bucket.admissionEvidenceId } + bucket)
            .sortedBy { it.admissionEvidenceId },
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

/** Durable novel-capability bucket. It never contains replacement-baseline/adoption evidence. */
internal data class NovelCapabilityVaultBucket(
    val admissionEvidenceId: String,
    val reservations: List<NovelCapabilityCanaryReservation> = emptyList(),
    val killSwitch: NovelCapabilityCanaryKillSwitchEvidence? = null,
    val outcomes: List<NovelCapabilityCanaryOutcome> = emptyList(),
) {
    init {
        require(admissionEvidenceId.isNotBlank()) { "Novel canary admission id must not be blank" }
        require(reservations.all { it.admissionEvidenceId == admissionEvidenceId }) {
            "Novel canary reservation belongs to another admission"
        }
        require(reservations.map { it.invocationId }.distinct().size == reservations.size) {
            "Novel canary cannot contain duplicate reservations"
        }
        require(reservations.map { it.id }.distinct().size == reservations.size) {
            "Novel canary cannot contain duplicate reservation evidence"
        }
        val orderedReservations = reservations.sortedBy { it.sequence }
        require(orderedReservations.map { it.sequence } == (1..orderedReservations.size).toList()) {
            "Novel canary reservation sequence must be contiguous"
        }
        require(killSwitch == null || killSwitch.admissionEvidenceId == admissionEvidenceId) {
            "Novel canary kill switch belongs to another admission"
        }
        require(outcomes.all { it.admissionEvidenceId == admissionEvidenceId }) {
            "Novel canary outcome belongs to another admission"
        }
        require(outcomes.map { it.invocationId }.distinct().size == outcomes.size) {
            "Novel canary cannot contain duplicate outcomes"
        }
        require(outcomes.map { it.id }.distinct().size == outcomes.size) {
            "Novel canary cannot contain duplicate outcome evidence"
        }
        require(outcomes.size <= reservations.size) {
            "Novel canary outcomes cannot exceed reservations"
        }
        val reservationByInvocation = reservations.associateBy { it.invocationId }
        outcomes.forEach { outcome ->
            val reservation = requireNotNull(reservationByInvocation[outcome.invocationId]) {
                "Novel canary outcome has no matching reservation"
            }
            require(reservation.id == outcome.reservationId) {
                "Novel canary outcome reservation evidence does not match vault"
            }
            require(reservation.toolId == outcome.toolId) {
                "Novel canary outcome tool differs from reservation"
            }
            require(reservation.candidateRecordFingerprint == outcome.candidateRecordFingerprint) {
                "Novel canary outcome record differs from reservation"
            }
        }
        val toolIds = buildSet {
            reservations.mapTo(this) { it.toolId }
            outcomes.mapTo(this) { it.toolId }
            killSwitch?.toolId?.let(::add)
        }
        require(toolIds.size <= 1) { "Novel canary bucket cannot mix tool identities" }
        val recordFingerprints = buildSet {
            reservations.mapTo(this) { it.candidateRecordFingerprint }
            outcomes.mapTo(this) { it.candidateRecordFingerprint }
        }
        require(recordFingerprints.size <= 1) { "Novel canary bucket cannot mix candidate records" }

        val safetyOutcomes = outcomes.filter { it.safetyViolation }
        if (safetyOutcomes.isNotEmpty()) {
            require(safetyOutcomes.size == 1) {
                "Novel canary cannot contain outcomes after the first safety violation"
            }
            val safety = safetyOutcomes.single()
            require(killSwitch != null) { "Novel canary safety violation requires atomic stop evidence" }
            require(killSwitch.reason == NovelCapabilityCanaryStopReason.SAFETY_VIOLATION)
            require(killSwitch.triggerEvidenceId == safety.id) {
                "Novel canary stop must reference the persisted safety outcome"
            }
        }
    }
}

/** Strict binary codec kept Android-free so corruption and rehydration invariants are JVM-tested. */
internal object EvolutionVaultCodec {
    const val VERSION = 2
    private const val LEGACY_VERSION = 1
    private const val MAGIC = 0x4C45564F // LEVO
    private const val MAX_BUCKETS = 512
    private const val MAX_ENTRIES_PER_BUCKET = 10_000
    private const val MAX_STRING_BYTES = 16 * 1024
    private const val MAX_FAILURES = 32

    fun supportsVersion(version: Int): Boolean = version in LEGACY_VERSION..VERSION

    fun encode(snapshot: EvolutionVaultSnapshot): ByteArray {
        require(snapshot.buckets.size <= MAX_BUCKETS) { "Too many evolution vault buckets" }
        require(snapshot.novelBuckets.size <= MAX_BUCKETS) { "Too many novel canary vault buckets" }
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                val buckets = snapshot.buckets.sortedBy { it.adoptionEvidenceId }
                data.writeInt(buckets.size)
                buckets.forEach { data.writeBucket(it) }
                val novelBuckets = snapshot.novelBuckets.sortedBy { it.admissionEvidenceId }
                data.writeInt(novelBuckets.size)
                novelBuckets.forEach { data.writeNovelBucket(it) }
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray, expectedVersion: Int? = null): EvolutionVaultSnapshot {
        require(bytes.isNotEmpty()) { "Evolution vault payload is empty" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid evolution vault magic" }
            val version = data.readInt()
            require(supportsVersion(version)) { "Unsupported evolution vault version" }
            require(expectedVersion == null || expectedVersion == version) {
                "Evolution vault container/payload version mismatch"
            }
            val bucketCount = data.readBoundedCount(MAX_BUCKETS, "adoption buckets")
            val buckets = List(bucketCount) { data.readBucket() }
            val novelBuckets = if (version >= VERSION) {
                val novelCount = data.readBoundedCount(MAX_BUCKETS, "novel admission buckets")
                List(novelCount) { data.readNovelBucket() }
            } else {
                emptyList()
            }
            require(data.read() == -1) { "Evolution vault contains trailing bytes" }
            EvolutionVaultSnapshot(buckets = buckets, novelBuckets = novelBuckets)
        }
    }

    private fun DataOutputStream.writeBucket(bucket: EvolutionVaultBucket) {
        writeText(bucket.adoptionEvidenceId)
        require(bucket.reservations.size <= MAX_ENTRIES_PER_BUCKET)
        val reservations = bucket.reservations.sortedBy { it.sequence }
        writeInt(reservations.size)
        reservations.forEach { writeReservation(it) }

        writeBoolean(bucket.killSwitch != null)
        bucket.killSwitch?.let { writeKillSwitch(it) }

        writeBoolean(bucket.promotionSeal != null)
        bucket.promotionSeal?.let { writePromotionSeal(it) }

        require(bucket.outcomes.size <= MAX_ENTRIES_PER_BUCKET)
        val outcomes = bucket.outcomes.sortedBy { it.invocationId }
        writeInt(outcomes.size)
        outcomes.forEach { writeOutcome(it) }
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

    private fun DataOutputStream.writeNovelBucket(bucket: NovelCapabilityVaultBucket) {
        writeText(bucket.admissionEvidenceId)
        require(bucket.reservations.size <= MAX_ENTRIES_PER_BUCKET)
        val reservations = bucket.reservations.sortedBy { it.sequence }
        writeInt(reservations.size)
        reservations.forEach { writeNovelReservation(it) }
        writeBoolean(bucket.killSwitch != null)
        bucket.killSwitch?.let { writeNovelKillSwitch(it) }
        require(bucket.outcomes.size <= MAX_ENTRIES_PER_BUCKET)
        val outcomes = bucket.outcomes.sortedBy { it.invocationId }
        writeInt(outcomes.size)
        outcomes.forEach { writeNovelOutcome(it) }
    }

    private fun DataInputStream.readNovelBucket(): NovelCapabilityVaultBucket {
        val admissionEvidenceId = readText()
        val reservations = List(readBoundedCount(MAX_ENTRIES_PER_BUCKET, "novel reservations")) {
            readNovelReservation().also { require(it.admissionEvidenceId == admissionEvidenceId) }
        }
        val killSwitch = if (readBoolean()) readNovelKillSwitch() else null
        val outcomes = List(readBoundedCount(MAX_ENTRIES_PER_BUCKET, "novel outcomes")) { readNovelOutcome() }
        return NovelCapabilityVaultBucket(
            admissionEvidenceId = admissionEvidenceId,
            reservations = reservations,
            killSwitch = killSwitch,
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

    private fun DataOutputStream.writeNovelReservation(value: NovelCapabilityCanaryReservation) {
        writeText(value.id)
        writeText(value.admissionEvidenceId)
        writeText(value.toolId)
        writeText(value.candidateRecordFingerprint)
        writeText(value.invocationId)
        writeText(value.inputFingerprint)
        writeText(value.expectedOutputFingerprint)
        writeInt(value.sequence)
        writeInstant(value.reservedAt)
    }

    private fun DataInputStream.readNovelReservation(): NovelCapabilityCanaryReservation {
        val expectedId = readText()
        val value = NovelCapabilityCanaryReservation(
            admissionEvidenceId = readText(),
            toolId = readText(),
            candidateRecordFingerprint = readText(),
            invocationId = readText(),
            inputFingerprint = readText(),
            expectedOutputFingerprint = readText(),
            sequence = readInt(),
            reservedAt = readInstant(),
        )
        require(value.id == expectedId) { "Novel canary reservation id integrity check failed" }
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

    private fun DataOutputStream.writeNovelKillSwitch(value: NovelCapabilityCanaryKillSwitchEvidence) {
        writeText(value.id)
        writeText(value.admissionEvidenceId)
        writeText(value.toolId)
        writeText(value.reason.name)
        writeText(value.triggerEvidenceId)
        writeInstant(value.trippedAt)
    }

    private fun DataInputStream.readNovelKillSwitch(): NovelCapabilityCanaryKillSwitchEvidence {
        val expectedId = readText()
        val value = NovelCapabilityCanaryKillSwitchEvidence(
            admissionEvidenceId = readText(),
            toolId = readText(),
            reason = enumValueOf<NovelCapabilityCanaryStopReason>(readText()),
            triggerEvidenceId = readText(),
            trippedAt = readInstant(),
        )
        require(value.id == expectedId) { "Novel canary kill-switch id integrity check failed" }
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

    private fun DataOutputStream.writeNovelOutcome(value: NovelCapabilityCanaryOutcome) {
        writeText(value.id)
        writeText(value.admissionEvidenceId)
        writeText(value.reservationId)
        writeText(value.toolId)
        writeText(value.candidateRecordFingerprint)
        writeText(value.invocationId)
        writeText(value.trialResultFingerprint)
        writeBoolean(value.success)
        writeBoolean(value.producedExpectedOutput)
        writeBoolean(value.safetyViolation)
        writeLong(value.latencyMs)
        writeInstant(value.recordedAt)
    }

    private fun DataInputStream.readNovelOutcome(): NovelCapabilityCanaryOutcome {
        val expectedId = readText()
        val value = NovelCapabilityCanaryOutcome(
            admissionEvidenceId = readText(),
            reservationId = readText(),
            toolId = readText(),
            candidateRecordFingerprint = readText(),
            invocationId = readText(),
            trialResultFingerprint = readText(),
            success = readBoolean(),
            producedExpectedOutput = readBoolean(),
            safetyViolation = readBoolean(),
            latencyMs = readLong(),
            recordedAt = readInstant(),
        )
        require(value.id == expectedId) { "Novel canary outcome id integrity check failed" }
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
