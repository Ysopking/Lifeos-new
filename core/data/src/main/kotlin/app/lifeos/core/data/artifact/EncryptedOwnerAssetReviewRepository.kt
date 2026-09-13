package app.lifeos.core.data.artifact

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidate
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidateId
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCodec
import app.lifeos.core.runtime.artifact.OwnerAssetReviewDecision
import app.lifeos.core.runtime.artifact.OwnerAssetReviewDecisionRecord
import app.lifeos.core.runtime.artifact.OwnerAssetReviewRecord
import app.lifeos.core.runtime.artifact.OwnerAssetReviewRepository
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

/**
 * Separate encrypted source of truth for generated assets awaiting private-owner review.
 *
 * Pending Photons deliberately never enter EncryptedPhotonStore through this repository. That keeps
 * boot rehydration and ordinary module discovery from seeing an unapproved revision. The exact
 * staged Photons are encrypted inside this vault and become canonical only through the owner-review
 * publication coordinator after an APPROVED decision.
 */
class EncryptedOwnerAssetReviewRepository(context: Context) : OwnerAssetReviewRepository {
    private val directory = context.filesDir.resolve("owner-asset-review-vault")
    private val target = AtomicFile(directory.resolve(VAULT_FILE_NAME))
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun stage(candidate: OwnerAssetReviewCandidate): OwnerAssetReviewRecord = ioLocked {
        val records = readRecordsLocked()
        val existing = records.firstOrNull { it.candidate.id == candidate.id }
        if (existing != null) {
            require(existing.candidate == candidate) {
                "Owner asset review candidate identity was reused with different content"
            }
            return@ioLocked existing
        }
        val record = OwnerAssetReviewRecord(candidate = candidate)
        writeRecordsLocked(records + record)
        record
    }

    override suspend fun load(candidateId: OwnerAssetReviewCandidateId): OwnerAssetReviewRecord? = ioLocked {
        readRecordsLocked().firstOrNull { it.candidate.id == candidateId }
    }

    override suspend fun loadAll(): List<OwnerAssetReviewRecord> = ioLocked {
        readRecordsLocked()
    }

    override suspend fun recordDecision(
        decision: OwnerAssetReviewDecisionRecord,
    ): OwnerAssetReviewRecord = ioLocked {
        val records = readRecordsLocked()
        val index = records.indexOfFirst { it.candidate.id == decision.candidateId }
        require(index >= 0) {
            "Owner asset review candidate ${decision.candidateId.value} does not exist"
        }
        val current = records[index]
        current.decision?.let { existing ->
            require(existing == decision) {
                "Owner asset review decision is immutable once recorded"
            }
            return@ioLocked current
        }
        val updated = current.copy(decision = decision)
        writeRecordsLocked(records.toMutableList().also { it[index] = updated })
        updated
    }

    override suspend fun markPublished(
        candidateId: OwnerAssetReviewCandidateId,
        publishedAt: Instant,
    ): OwnerAssetReviewRecord = ioLocked {
        val records = readRecordsLocked()
        val index = records.indexOfFirst { it.candidate.id == candidateId }
        require(index >= 0) { "Owner asset review candidate ${candidateId.value} does not exist" }
        val current = records[index]
        val decision = requireNotNull(current.decision) {
            "Owner asset review candidate cannot be published before a decision"
        }
        require(decision.decision == OwnerAssetReviewDecision.APPROVED) {
            "Only an approved owner asset review candidate may be published"
        }
        current.publishedAt?.let { return@ioLocked current }

        val updated = current.copy(publishedAt = publishedAt)
        writeRecordsLocked(records.toMutableList().also { it[index] = updated })
        updated
    }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock { block() }
    }

    private fun readRecordsLocked(): List<OwnerAssetReviewRecord> {
        ensureDirectory()
        val backup = directory.resolve("$VAULT_FILE_NAME.bak")
        if (!target.baseFile.exists() && !backup.exists()) return emptyList()

        val container = target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CONTAINER_BYTES) {
                    "Owner asset review vault file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decrypt(container)
    }

    private fun writeRecordsLocked(records: List<OwnerAssetReviewRecord>) {
        ensureDirectory()
        val plaintext = OwnerAssetReviewCodec.encode(records)
        require(plaintext.size <= OwnerAssetReviewCodec.MAX_PLAINTEXT_BYTES) {
            "Owner asset review vault payload too large"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plaintext)
        val container = ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION)
                data.writeInt(OwnerAssetReviewCodec.VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.write(encrypted)
            }
        }.toByteArray()
        require(container.size <= MAX_CONTAINER_BYTES) {
            "Owner asset review vault container too large"
        }

        val stream = target.startWrite()
        try {
            stream.write(container)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun decrypt(container: ByteArray): List<OwnerAssetReviewRecord> {
        require(container.size <= MAX_CONTAINER_BYTES) { "Owner asset review vault file too large" }
        return DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION) { "Unsupported owner asset review container" }
            require(data.readInt() == OwnerAssetReviewCodec.VERSION) {
                "Unsupported owner asset review codec"
            }
            val ivSize = data.readInt()
            require(ivSize in 12..32) { "Invalid owner asset review IV length" }
            val iv = ByteArray(ivSize).also(data::readFully)
            val encrypted = data.readBytes()
            require(encrypted.isNotEmpty()) { "Missing owner asset review ciphertext" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            OwnerAssetReviewCodec.decode(cipher.doFinal(encrypted))
        }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Owner asset review vault unavailable"
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        val processMutex = Mutex()
        const val KEY_ALIAS = "lifeos.owner-asset-review.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val VAULT_FILE_NAME = "owner-asset-review.records"
        const val MAX_CONTAINER_BYTES = OwnerAssetReviewCodec.MAX_PLAINTEXT_BYTES + 64 * 1024
    }
}
