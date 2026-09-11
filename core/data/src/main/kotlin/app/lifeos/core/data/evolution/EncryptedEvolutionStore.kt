package app.lifeos.core.data.evolution

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.evolution.EvolutionCanaryKillSwitchEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcome
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcomeStore
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcomeWriteResult
import app.lifeos.core.runtime.evolution.EvolutionCanaryPromotionSealEvidence
import app.lifeos.core.runtime.evolution.EvolutionCanaryReservation
import app.lifeos.core.runtime.evolution.EvolutionCanaryReserveResult
import app.lifeos.core.runtime.evolution.EvolutionCanaryStopReason
import app.lifeos.core.runtime.evolution.EvolutionPromotionRuntimeStore
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryKillSwitchEvidence
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryOutcome
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryOutcomeWriteResult
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReservation
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReservationRequest
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReserveResult
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryStopReason
import app.lifeos.core.runtime.evolution.NovelCapabilityPromotionSealEvidence
import app.lifeos.core.runtime.evolution.NovelCapabilityPromotionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One encrypted atomic safety domain for replacement and novel generated-tool evolution. */
class EncryptedEvolutionStore(context: Context) :
    EvolutionPromotionRuntimeStore,
    EvolutionCanaryOutcomeStore,
    NovelCapabilityPromotionStore {
    private val directory = context.filesDir.resolve("evolution-vault")
    private val target = AtomicFile(directory.resolve(VAULT_FILE_NAME))
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun reserve(
        adoptionEvidenceId: String,
        invocationId: String,
        maxInvocations: Int,
        reservedAt: Instant,
    ): EvolutionCanaryReserveResult = ioLocked {
        require(adoptionEvidenceId.isNotBlank()); require(invocationId.isNotBlank()); require(maxInvocations > 0)
        val snapshot = readSnapshotLocked(); val bucket = snapshot.bucket(adoptionEvidenceId)
        bucket.killSwitch?.let { return@ioLocked EvolutionCanaryReserveResult.Stopped(it) }
        bucket.promotionSeal?.let { return@ioLocked EvolutionCanaryReserveResult.Sealed(it) }
        bucket.reservations.firstOrNull { it.invocationId == invocationId }?.let {
            return@ioLocked EvolutionCanaryReserveResult.Reserved(it, duplicate = true)
        }
        if (bucket.reservations.size >= maxInvocations) {
            return@ioLocked EvolutionCanaryReserveResult.Exhausted(bucket.reservations.size)
        }
        val reservation = EvolutionCanaryReservation(
            adoptionEvidenceId = adoptionEvidenceId,
            invocationId = invocationId,
            sequence = bucket.reservations.size + 1,
            reservedAt = reservedAt,
        )
        writeSnapshotLocked(snapshot.withBucket(bucket.copy(reservations = bucket.reservations + reservation)))
        EvolutionCanaryReserveResult.Reserved(reservation, duplicate = false)
    }

    override suspend fun reservation(adoptionEvidenceId: String, invocationId: String): EvolutionCanaryReservation? = ioLocked {
        readSnapshotLocked().bucket(adoptionEvidenceId).reservations.firstOrNull { it.invocationId == invocationId }
    }

    override suspend fun usedInvocations(adoptionEvidenceId: String): Int = ioLocked {
        readSnapshotLocked().bucket(adoptionEvidenceId).reservations.size
    }

    override suspend fun trip(evidence: EvolutionCanaryKillSwitchEvidence): EvolutionCanaryKillSwitchEvidence = ioLocked {
        val snapshot = readSnapshotLocked(); val bucket = snapshot.bucket(evidence.adoptionEvidenceId)
        bucket.killSwitch?.let { return@ioLocked it }
        bucket.promotionSeal?.let { seal ->
            require(seal.candidateToolId == evidence.candidateToolId) { "Kill switch candidate conflicts with sealed Canary" }
        }
        val known = bucket.outcomes.map { it.candidateToolId }.toSet()
        require(known.isEmpty() || known == setOf(evidence.candidateToolId))
        writeSnapshotLocked(snapshot.withBucket(bucket.copy(killSwitch = evidence)))
        evidence
    }

    override suspend fun killSwitch(adoptionEvidenceId: String): EvolutionCanaryKillSwitchEvidence? = ioLocked {
        readSnapshotLocked().bucket(adoptionEvidenceId).killSwitch
    }

    override suspend fun sealForPromotion(
        adoptionEvidenceId: String,
        candidateToolId: String,
        readinessEvidenceId: String,
        expectedReservedInvocations: Int,
        sealedAt: Instant,
    ): EvolutionCanaryPromotionSealEvidence = ioLocked {
        require(adoptionEvidenceId.isNotBlank()); require(candidateToolId.isNotBlank()); require(readinessEvidenceId.isNotBlank())
        require(expectedReservedInvocations >= 0)
        val snapshot = readSnapshotLocked(); val bucket = snapshot.bucket(adoptionEvidenceId)
        require(bucket.killSwitch == null) { "Stopped canary cannot be sealed for promotion" }
        require(bucket.reservations.size == expectedReservedInvocations)
        require(bucket.outcomes.size == expectedReservedInvocations)
        val known = bucket.outcomes.map { it.candidateToolId }.toSet()
        require(known.isEmpty() || known == setOf(candidateToolId))
        bucket.promotionSeal?.let { existing ->
            require(existing.candidateToolId == candidateToolId)
            require(existing.readinessEvidenceId == readinessEvidenceId)
            require(existing.expectedReservedInvocations == expectedReservedInvocations)
            return@ioLocked existing
        }
        val seal = EvolutionCanaryPromotionSealEvidence(
            adoptionEvidenceId = adoptionEvidenceId,
            candidateToolId = candidateToolId,
            readinessEvidenceId = readinessEvidenceId,
            expectedReservedInvocations = expectedReservedInvocations,
            sealedAt = sealedAt,
        )
        writeSnapshotLocked(snapshot.withBucket(bucket.copy(promotionSeal = seal)))
        seal
    }

    override suspend fun promotionSeal(adoptionEvidenceId: String): EvolutionCanaryPromotionSealEvidence? = ioLocked {
        readSnapshotLocked().bucket(adoptionEvidenceId).promotionSeal
    }

    override suspend fun record(outcome: EvolutionCanaryOutcome): EvolutionCanaryOutcomeWriteResult = ioLocked {
        val snapshot = readSnapshotLocked(); val bucket = snapshot.bucket(outcome.adoptionEvidenceId)
        bucket.outcomes.firstOrNull { it.invocationId == outcome.invocationId }?.let { existing ->
            return@ioLocked if (existing.inputFingerprint == outcome.inputFingerprint) {
                EvolutionCanaryOutcomeWriteResult.Duplicate(existing)
            } else EvolutionCanaryOutcomeWriteResult.Conflict(existing.id)
        }
        require(bucket.killSwitch == null) { "Cannot persist a new Canary outcome after kill switch" }
        require(bucket.promotionSeal == null) { "Cannot persist a new Canary outcome after promotion seal" }
        val reservation = requireNotNull(bucket.reservations.firstOrNull { it.invocationId == outcome.invocationId })
        require(reservation.id == outcome.reservationId)
        val known = bucket.outcomes.map { it.candidateToolId }.toSet()
        require(known.isEmpty() || known == setOf(outcome.candidateToolId))
        val stop = outcome.takeIf { it.hardFailures.isNotEmpty() }?.let {
            EvolutionCanaryKillSwitchEvidence(
                adoptionEvidenceId = it.adoptionEvidenceId,
                candidateToolId = it.candidateToolId,
                reason = EvolutionCanaryStopReason.HARD_FAILURE,
                triggerOutcomeId = it.id,
                hardFailures = it.hardFailures,
                trippedAt = it.recordedAt,
            )
        }
        writeSnapshotLocked(snapshot.withBucket(bucket.copy(outcomes = bucket.outcomes + outcome, killSwitch = stop)))
        EvolutionCanaryOutcomeWriteResult.Recorded(outcome)
    }

    override suspend fun outcome(adoptionEvidenceId: String, invocationId: String): EvolutionCanaryOutcome? = ioLocked {
        readSnapshotLocked().bucket(adoptionEvidenceId).outcomes.firstOrNull { it.invocationId == invocationId }
    }

    override suspend fun outcomes(adoptionEvidenceId: String): List<EvolutionCanaryOutcome> = ioLocked {
        readSnapshotLocked().bucket(adoptionEvidenceId).outcomes.sortedBy { it.invocationId }
    }

    override suspend fun reserveNovel(request: NovelCapabilityCanaryReservationRequest): NovelCapabilityCanaryReserveResult = ioLocked {
        val snapshot = readSnapshotLocked(); val bucket = snapshot.novelBucket(request.admissionEvidenceId)
        require(bucket.promotionSeal == null) { "Novel canary is sealed for promotion" }
        bucket.killSwitch?.let { return@ioLocked NovelCapabilityCanaryReserveResult.Stopped(it) }
        bucket.reservations.firstOrNull { it.invocationId == request.invocationId }?.let { existing ->
            require(existing.toolId == request.toolId)
            require(existing.candidateRecordFingerprint == request.candidateRecordFingerprint)
            require(existing.inputFingerprint == request.inputFingerprint)
            require(existing.expectedOutputFingerprint == request.expectedOutputFingerprint)
            return@ioLocked NovelCapabilityCanaryReserveResult.Reserved(existing, duplicate = true)
        }
        if (bucket.reservations.size >= request.maxInvocations) {
            return@ioLocked NovelCapabilityCanaryReserveResult.Exhausted(bucket.reservations.size)
        }
        val knownTools = bucket.reservations.map { it.toolId }.toSet()
        val knownRecords = bucket.reservations.map { it.candidateRecordFingerprint }.toSet()
        require(knownTools.isEmpty() || knownTools == setOf(request.toolId))
        require(knownRecords.isEmpty() || knownRecords == setOf(request.candidateRecordFingerprint))
        val reservation = NovelCapabilityCanaryReservation(
            admissionEvidenceId = request.admissionEvidenceId,
            toolId = request.toolId,
            candidateRecordFingerprint = request.candidateRecordFingerprint,
            invocationId = request.invocationId,
            inputFingerprint = request.inputFingerprint,
            expectedOutputFingerprint = request.expectedOutputFingerprint,
            sequence = bucket.reservations.size + 1,
            reservedAt = request.reservedAt,
        )
        writeSnapshotLocked(snapshot.withNovelBucket(bucket.copy(reservations = bucket.reservations + reservation)))
        NovelCapabilityCanaryReserveResult.Reserved(reservation, duplicate = false)
    }

    override suspend fun novelReservation(admissionEvidenceId: String, invocationId: String): NovelCapabilityCanaryReservation? = ioLocked {
        readSnapshotLocked().novelBucket(admissionEvidenceId).reservations.firstOrNull { it.invocationId == invocationId }
    }

    override suspend fun novelReservations(admissionEvidenceId: String): List<NovelCapabilityCanaryReservation> = ioLocked {
        readSnapshotLocked().novelBucket(admissionEvidenceId).reservations.sortedBy { it.sequence }
    }

    override suspend fun recordNovelOutcome(outcome: NovelCapabilityCanaryOutcome): NovelCapabilityCanaryOutcomeWriteResult = ioLocked {
        val snapshot = readSnapshotLocked(); val bucket = snapshot.novelBucket(outcome.admissionEvidenceId)
        bucket.outcomes.firstOrNull { it.invocationId == outcome.invocationId }?.let { existing ->
            return@ioLocked if (existing == outcome) {
                NovelCapabilityCanaryOutcomeWriteResult.Duplicate(existing, bucket.killSwitch)
            } else NovelCapabilityCanaryOutcomeWriteResult.Conflict(existing.id)
        }
        require(bucket.killSwitch == null) { "Cannot persist novel Canary outcome after stop" }
        require(bucket.promotionSeal == null) { "Cannot persist novel Canary outcome after promotion seal" }
        val reservation = requireNotNull(bucket.reservations.firstOrNull { it.invocationId == outcome.invocationId })
        require(reservation.id == outcome.reservationId)
        require(reservation.toolId == outcome.toolId)
        require(reservation.candidateRecordFingerprint == outcome.candidateRecordFingerprint)
        val stop = outcome.takeIf { it.safetyViolation }?.let {
            NovelCapabilityCanaryKillSwitchEvidence(
                admissionEvidenceId = it.admissionEvidenceId,
                toolId = it.toolId,
                reason = NovelCapabilityCanaryStopReason.SAFETY_VIOLATION,
                triggerEvidenceId = it.id,
                trippedAt = it.recordedAt,
            )
        }
        writeSnapshotLocked(snapshot.withNovelBucket(bucket.copy(outcomes = bucket.outcomes + outcome, killSwitch = stop)))
        NovelCapabilityCanaryOutcomeWriteResult.Recorded(outcome, stop)
    }

    override suspend fun novelOutcome(admissionEvidenceId: String, invocationId: String): NovelCapabilityCanaryOutcome? = ioLocked {
        readSnapshotLocked().novelBucket(admissionEvidenceId).outcomes.firstOrNull { it.invocationId == invocationId }
    }

    override suspend fun novelOutcomes(admissionEvidenceId: String): List<NovelCapabilityCanaryOutcome> = ioLocked {
        readSnapshotLocked().novelBucket(admissionEvidenceId).outcomes.sortedBy { it.invocationId }
    }

    override suspend fun tripNovel(evidence: NovelCapabilityCanaryKillSwitchEvidence): NovelCapabilityCanaryKillSwitchEvidence = ioLocked {
        val snapshot = readSnapshotLocked(); val bucket = snapshot.novelBucket(evidence.admissionEvidenceId)
        require(bucket.promotionSeal == null) { "Novel canary is sealed for promotion" }
        bucket.killSwitch?.let { existing -> require(existing.toolId == evidence.toolId); return@ioLocked existing }
        val known = buildSet {
            bucket.reservations.mapTo(this) { it.toolId }; bucket.outcomes.mapTo(this) { it.toolId }
        }
        require(known.isEmpty() || known == setOf(evidence.toolId))
        writeSnapshotLocked(snapshot.withNovelBucket(bucket.copy(killSwitch = evidence)))
        evidence
    }

    override suspend fun novelKillSwitch(admissionEvidenceId: String): NovelCapabilityCanaryKillSwitchEvidence? = ioLocked {
        readSnapshotLocked().novelBucket(admissionEvidenceId).killSwitch
    }

    override suspend fun sealNovelForPromotion(
        admissionEvidenceId: String,
        subjectId: String,
        toolId: String,
        candidateRecordFingerprint: String,
        artifactId: String,
        readinessEvidenceId: String,
        expectedReservedInvocations: Int,
        sealedAt: Instant,
    ): NovelCapabilityPromotionSealEvidence = ioLocked {
        val snapshot = readSnapshotLocked(); val bucket = snapshot.novelBucket(admissionEvidenceId)
        bucket.promotionSeal?.let { existing ->
            require(existing.subjectId == subjectId); require(existing.toolId == toolId)
            require(existing.candidateRecordFingerprint == candidateRecordFingerprint)
            require(existing.artifactId == artifactId); require(existing.readinessEvidenceId == readinessEvidenceId)
            require(existing.expectedReservedInvocations == expectedReservedInvocations)
            return@ioLocked existing
        }
        require(bucket.killSwitch == null) { "Stopped novel canary cannot be sealed for promotion" }
        require(bucket.reservations.size == expectedReservedInvocations) {
            "Novel canary reservation count changed before promotion seal"
        }
        require(bucket.outcomes.size == expectedReservedInvocations) {
            "Novel canary cannot be sealed while outcomes are pending"
        }
        require(bucket.reservations.map { it.toolId }.toSet() == setOf(toolId))
        require(bucket.outcomes.map { it.toolId }.toSet() == setOf(toolId))
        require(bucket.reservations.map { it.candidateRecordFingerprint }.toSet() == setOf(candidateRecordFingerprint))
        require(bucket.outcomes.map { it.candidateRecordFingerprint }.toSet() == setOf(candidateRecordFingerprint))
        val seal = NovelCapabilityPromotionSealEvidence(
            admissionEvidenceId = admissionEvidenceId,
            subjectId = subjectId,
            toolId = toolId,
            candidateRecordFingerprint = candidateRecordFingerprint,
            artifactId = artifactId,
            readinessEvidenceId = readinessEvidenceId,
            expectedReservedInvocations = expectedReservedInvocations,
            sealedAt = sealedAt,
        )
        writeSnapshotLocked(snapshot.withNovelBucket(bucket.copy(promotionSeal = seal)))
        seal
    }

    override suspend fun novelPromotionSeal(admissionEvidenceId: String): NovelCapabilityPromotionSealEvidence? = ioLocked {
        readSnapshotLocked().novelBucket(admissionEvidenceId).promotionSeal
    }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock { block() }
    }

    private fun readSnapshotLocked(): EvolutionVaultSnapshot {
        ensureDirectory()
        if (!target.baseFile.exists() && !directory.resolve("$VAULT_FILE_NAME.bak").exists()) return EvolutionVaultSnapshot()
        val container = target.openRead().use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_CONTAINER_BYTES) { "Evolution vault file too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decrypt(container)
    }

    private fun writeSnapshotLocked(snapshot: EvolutionVaultSnapshot) {
        ensureDirectory()
        val plaintext = EvolutionVaultCodec.encode(snapshot)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Evolution vault payload too large" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plaintext)
        val container = ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION); data.writeInt(EvolutionVaultCodec.VERSION)
                data.writeInt(cipher.iv.size); data.write(cipher.iv); data.write(encrypted)
            }
        }.toByteArray()
        require(container.size <= MAX_CONTAINER_BYTES) { "Evolution vault container too large" }
        val stream = target.startWrite()
        try { stream.write(container); target.finishWrite(stream) } catch (error: Exception) {
            target.failWrite(stream); throw error
        }
    }

    private fun decrypt(container: ByteArray): EvolutionVaultSnapshot {
        require(container.size <= MAX_CONTAINER_BYTES) { "Evolution vault file too large" }
        return DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION) { "Unsupported evolution vault container" }
            val storedCodecVersion = data.readInt()
            require(EvolutionVaultCodec.supportsVersion(storedCodecVersion)) { "Unsupported evolution vault codec" }
            val ivSize = data.readInt(); require(ivSize in 12..32) { "Invalid evolution vault IV length" }
            val iv = ByteArray(ivSize).also(data::readFully); val encrypted = data.readBytes()
            require(encrypted.isNotEmpty()) { "Missing evolution vault ciphertext" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            EvolutionVaultCodec.decode(cipher.doFinal(encrypted), expectedVersion = storedCodecVersion)
        }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Evolution vault unavailable" }
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build(),
            )
            generateKey()
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.evolution.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val VAULT_FILE_NAME = "state.evolution"
        const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
        const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 64 * 1024
    }
}
