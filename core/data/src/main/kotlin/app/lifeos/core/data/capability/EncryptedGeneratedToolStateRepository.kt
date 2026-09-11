package app.lifeos.core.data.capability

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.capability.GeneratedToolAuditAction
import app.lifeos.core.runtime.capability.GeneratedToolAuditEntry
import app.lifeos.core.runtime.capability.GeneratedToolPersistentState
import app.lifeos.core.runtime.capability.GeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.GeneratedToolPromotionReceipt
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolStateCodec
import app.lifeos.core.runtime.capability.GeneratedToolStateRepository
import app.lifeos.core.runtime.capability.GeneratedToolTrialEvidence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted, atomic generated-tool lifecycle vault.
 *
 * Container v1 remains stable. The stored payload codec version is independently validated so old
 * codec-v1 ciphertext remains readable while every successful write upgrades the payload to the
 * current GeneratedToolStateCodec version.
 */
class EncryptedGeneratedToolStateRepository(context: Context) : GeneratedToolStateRepository {
    private val directory = context.filesDir.resolve("generated-tool-state-vault")
    private val target = AtomicFile(directory.resolve(VAULT_FILE_NAME))
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override suspend fun loadAll(): List<GeneratedToolPersistentState> = ioLocked {
        readStatesLocked()
    }

    override suspend fun persistLifecycle(
        record: GeneratedToolRecord,
        auditEntries: List<GeneratedToolAuditEntry>,
        promotionEvidence: GeneratedToolPromotionEvidence?,
    ) = ioLocked {
        val toolId = record.manifest.toolId
        val states = readStatesLocked()
        val existing = states.firstOrNull { it.record.manifest.toolId == toolId }

        if (existing == null) {
            require(auditEntries.size == 1 && auditEntries.single().action == GeneratedToolAuditAction.REGISTERED) {
                "New durable generated tool must begin with exactly one registration audit"
            }
        } else {
            require(existing.record.manifest == record.manifest) {
                "Durable generated-tool lifecycle cannot rewrite the manifest"
            }
            require(auditEntries.size == existing.auditEntries.size + 1) {
                "Durable generated-tool audit may append exactly one entry per mutation"
            }
            require(auditEntries.dropLast(1) == existing.auditEntries) {
                "Durable generated-tool audit history cannot be rewritten"
            }
        }

        val legacyReceipt: GeneratedToolPromotionReceipt?
        val boundedReceipt = when {
            record.promotionEvidenceId == null -> {
                legacyReceipt = null
                null
            }
            promotionEvidence != null -> {
                legacyReceipt = GeneratedToolPromotionReceipt.from(promotionEvidence).also {
                    require(it.evidenceId == record.promotionEvidenceId)
                }
                null
            }
            existing?.promotionReceipt != null -> {
                legacyReceipt = existing.promotionReceipt.also {
                    require(it.evidenceId == record.promotionEvidenceId) {
                        "Durable promotion receipt does not match current record"
                    }
                }
                null
            }
            existing?.boundedPromotionReceipt != null -> {
                legacyReceipt = null
                existing.boundedPromotionReceipt.also {
                    require(it.evidenceId == record.promotionEvidenceId) {
                        "Durable bounded promotion receipt does not match current record"
                    }
                }
            }
            else -> error("Durable promoted generated tool lost its promotion receipt")
        }
        val trials = existing?.trialEvidence ?: GeneratedToolTrialEvidence(toolId, emptyList())
        val updated = GeneratedToolPersistentState(
            record = record,
            auditEntries = auditEntries,
            trialEvidence = trials,
            promotionReceipt = legacyReceipt,
            boundedPromotionReceipt = boundedReceipt,
        )
        writeStatesLocked(replace(states, updated))
    }

    override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) = ioLocked {
        val states = readStatesLocked()
        val existing = requireNotNull(
            states.firstOrNull { it.record.manifest.toolId == evidence.toolId }
        ) { "Trial evidence requires a durably registered generated tool" }
        require(existing.promotionReceipt == null && existing.boundedPromotionReceipt == null) {
            "Promoted generated-tool trial evidence is sealed and cannot change"
        }

        val oldResults = existing.trialEvidence.orderedResults.associateBy { it.invocationId }
        val newResults = evidence.orderedResults.associateBy { it.invocationId }
        require(newResults.size == oldResults.size + 1) {
            "Durable generated-tool trial ledger may append exactly one invocation"
        }
        oldResults.forEach { (invocationId, old) ->
            require(newResults[invocationId] == old) {
                "Durable generated-tool trial history cannot be rewritten"
            }
        }

        val updated = GeneratedToolPersistentState(
            record = existing.record,
            auditEntries = existing.auditEntries,
            trialEvidence = evidence,
            promotionReceipt = null,
            boundedPromotionReceipt = null,
        )
        writeStatesLocked(replace(states, updated))
    }

    private fun replace(
        states: List<GeneratedToolPersistentState>,
        updated: GeneratedToolPersistentState,
    ): List<GeneratedToolPersistentState> =
        (states.filterNot { it.record.manifest.toolId == updated.record.manifest.toolId } + updated)
            .sortedBy { it.record.manifest.toolId }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock { block() }
    }

    private fun readStatesLocked(): List<GeneratedToolPersistentState> {
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
                    "Generated-tool state vault file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decrypt(container)
    }

    private fun writeStatesLocked(states: List<GeneratedToolPersistentState>) {
        ensureDirectory()
        val plaintext = GeneratedToolStateCodec.encode(states)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Generated-tool state vault payload too large" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plaintext)
        val container = ByteArrayOutputStream(encrypted.size + 64).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CONTAINER_VERSION)
                data.writeInt(GeneratedToolStateCodec.VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.write(encrypted)
            }
        }.toByteArray()
        require(container.size <= MAX_CONTAINER_BYTES) { "Generated-tool state vault container too large" }

        val stream = target.startWrite()
        try {
            stream.write(container)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun decrypt(container: ByteArray): List<GeneratedToolPersistentState> {
        require(container.size <= MAX_CONTAINER_BYTES) { "Generated-tool state vault file too large" }
        return DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == CONTAINER_VERSION) { "Unsupported generated-tool state container" }
            val storedCodecVersion = data.readInt()
            require(GeneratedToolStateCodec.supportsVersion(storedCodecVersion)) {
                "Unsupported generated-tool state codec"
            }
            val ivSize = data.readInt()
            require(ivSize in 12..32) { "Invalid generated-tool state IV length" }
            val iv = ByteArray(ivSize).also(data::readFully)
            val encrypted = data.readBytes()
            require(encrypted.isNotEmpty()) { "Missing generated-tool state ciphertext" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            GeneratedToolStateCodec.decode(
                bytes = cipher.doFinal(encrypted),
                expectedVersion = storedCodecVersion,
            )
        }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Generated-tool state vault unavailable" }
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
        const val KEY_ALIAS = "lifeos.generated-tools.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONTAINER_VERSION = 1
        const val VAULT_FILE_NAME = "state.tools"
        const val MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024
        const val MAX_CONTAINER_BYTES = MAX_PLAINTEXT_BYTES + 64 * 1024
    }
}
