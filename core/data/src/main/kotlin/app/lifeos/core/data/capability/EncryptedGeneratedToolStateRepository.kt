package app.lifeos.core.data.capability

import android.content.Context
import android.util.AtomicFile
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.capability.BoundedGeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.BoundedGeneratedToolPromotionReceipt
import app.lifeos.core.runtime.capability.BoundedGeneratedToolStateRepository
import app.lifeos.core.runtime.capability.GeneratedToolAuditAction
import app.lifeos.core.runtime.capability.GeneratedToolAuditEntry
import app.lifeos.core.runtime.capability.GeneratedToolPersistentState
import app.lifeos.core.runtime.capability.GeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.GeneratedToolPromotionReceipt
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolStateCodec
import app.lifeos.core.runtime.capability.GeneratedToolTrialEvidence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Segmented generated-tool lifecycle vault.
 *
 * Each tool owns one independently encrypted AtomicFile. Mutating one tool therefore never decrypts
 * or rewrites unrelated tools. The encrypted index is only an accelerator/recovery manifest; tool
 * segment contents remain authoritative and the index is rebuilt if a crash leaves an orphan
 * segment. The legacy monolithic state.tools vault is migrated lazily and idempotently.
 */
class EncryptedGeneratedToolStateRepository(context: Context) : BoundedGeneratedToolStateRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val toolsDirectory = directory.resolve(TOOLS_DIRECTORY)
    private val indexFile = directory.resolve(INDEX_FILE_NAME)
    private val legacyTarget = AtomicFile(directory.resolve(LEGACY_VAULT_FILE_NAME))
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun loadAll(): List<GeneratedToolPersistentState> = ioLocked {
        initializeSegmentedStoreLocked()
        val states = readAllSegmentStatesLocked()
        reconcileIndexLocked(states.map { it.record.manifest.toolId }.toSortedSet())
        states.sortedBy { it.record.manifest.toolId }
    }

    override suspend fun persistLifecycle(
        record: GeneratedToolRecord,
        auditEntries: List<GeneratedToolAuditEntry>,
        promotionEvidence: GeneratedToolPromotionEvidence?,
    ) = ioLocked {
        initializeSegmentedStoreLocked()
        val toolId = record.manifest.toolId
        val existing = readToolStateLocked(toolId)
        requireLifecycleAppend(existing, record, auditEntries)

        val existingLegacyReceipt = existing?.promotionReceipt
        val existingBoundedReceipt = existing?.boundedPromotionReceipt
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
            existingLegacyReceipt != null -> {
                legacyReceipt = existingLegacyReceipt.also {
                    require(it.evidenceId == record.promotionEvidenceId) {
                        "Durable promotion receipt does not match current record"
                    }
                }
                null
            }
            existingBoundedReceipt != null -> {
                legacyReceipt = null
                existingBoundedReceipt.also {
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
        writeToolStateLocked(updated)
        ensureIndexedLocked(toolId)
    }

    override suspend fun persistBoundedLifecycle(
        record: GeneratedToolRecord,
        auditEntries: List<GeneratedToolAuditEntry>,
        promotionEvidence: BoundedGeneratedToolPromotionEvidence,
    ) = ioLocked {
        initializeSegmentedStoreLocked()
        val toolId = record.manifest.toolId
        val existing = requireNotNull(readToolStateLocked(toolId)) {
            "Bounded promotion requires an already durable generated tool"
        }
        requireLifecycleAppend(existing, record, auditEntries)
        require(existing.promotionReceipt == null && existing.boundedPromotionReceipt == null) {
            "Bounded promotion cannot replace an existing promotion receipt"
        }
        require(record.promotionEvidenceId == promotionEvidence.id) {
            "Bounded promotion record does not reference exact activation evidence"
        }
        require(existing.trialEvidence.id == promotionEvidence.trialEvidenceId) {
            "Bounded promotion evidence does not bind exact durable trial evidence"
        }
        val receipt = BoundedGeneratedToolPromotionReceipt(
            evidenceId = promotionEvidence.id,
            toolId = promotionEvidence.toolId,
            artifactId = promotionEvidence.artifactId,
            recordFingerprint = promotionEvidence.recordFingerprint,
            trialEvidenceId = promotionEvidence.trialEvidenceId,
            promotionPolicyFingerprint = promotionEvidence.promotionPolicyFingerprint,
            novelAdmissionEvidenceId = promotionEvidence.novelAdmissionEvidenceId,
            canaryReadinessEvidenceId = promotionEvidence.canaryReadinessEvidenceId,
            promotionSealId = promotionEvidence.promotionSealId,
            reviewerEvidenceFingerprints = promotionEvidence.reviewerEvidenceFingerprints,
            activationActorEvidenceFingerprints = promotionEvidence.activationActorEvidenceFingerprints,
        )
        require(receipt.evidenceId == record.promotionEvidenceId)
        writeToolStateLocked(
            GeneratedToolPersistentState(
                record = record,
                auditEntries = auditEntries,
                trialEvidence = existing.trialEvidence,
                boundedPromotionReceipt = receipt,
            )
        )
        ensureIndexedLocked(toolId)
    }

    override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) = ioLocked {
        initializeSegmentedStoreLocked()
        val existing = requireNotNull(readToolStateLocked(evidence.toolId)) {
            "Trial evidence requires a durably registered generated tool"
        }
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
        writeToolStateLocked(
            GeneratedToolPersistentState(
                record = existing.record,
                auditEntries = existing.auditEntries,
                trialEvidence = evidence,
            )
        )
        ensureIndexedLocked(evidence.toolId)
    }

    private fun initializeSegmentedStoreLocked() {
        ensureDirectories()
        if (!exists(indexFile)) {
            migrateLegacyLocked()
            val discovered = readAllSegmentStatesLocked()
                .map { it.record.manifest.toolId }
                .toSortedSet()
            writeIndexLocked(discovered)
        }
    }

    /**
     * Migration is crash-safe because existing segment files are never overwritten by legacy data.
     * A crash before index publication simply reruns migration and then rebuilds the index.
     */
    private fun migrateLegacyLocked() {
        if (!exists(legacyTarget.baseFile)) return
        readLegacyStatesLocked().forEach { legacyState ->
            val toolId = legacyState.record.manifest.toolId
            val target = fileForToolId(toolId)
            if (!exists(target)) {
                writeToolStateLocked(legacyState)
            } else {
                val segmented = readToolStateLocked(toolId)
                require(segmented != null)
                require(segmented.record.manifest == legacyState.record.manifest) {
                    "Legacy/segmented generated-tool manifest mismatch for $toolId"
                }
            }
        }
    }

    private fun readToolStateLocked(toolId: String): GeneratedToolPersistentState? {
        require(toolId.isNotBlank())
        val file = fileForToolId(toolId)
        if (!exists(file)) return null
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_TOOL_PLAINTEXT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_TOOL_PLAINTEXT_BYTES,
        )
        val states = GeneratedToolStateCodec.decode(plaintext)
        require(states.size == 1) { "Generated-tool segment must contain exactly one tool" }
        return states.single().also { state ->
            require(state.record.manifest.toolId == toolId) {
                "Generated-tool segment identity mismatch"
            }
        }
    }

    private fun readAllSegmentStatesLocked(): List<GeneratedToolPersistentState> {
        val states = segmentBaseFiles().map { file ->
            val plaintext = EncryptedLedgerVaultSupport.decrypt(
                container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_TOOL_PLAINTEXT_BYTES),
                key = key,
                maxPlaintextBytes = MAX_TOOL_PLAINTEXT_BYTES,
            )
            val decoded = GeneratedToolStateCodec.decode(plaintext)
            require(decoded.size == 1) { "Generated-tool segment must contain exactly one tool" }
            decoded.single().also { state ->
                require(file.name == fileForToolId(state.record.manifest.toolId).name) {
                    "Generated-tool segment filename/content mismatch"
                }
            }
        }
        require(states.map { it.record.manifest.toolId }.distinct().size == states.size) {
            "Segmented generated-tool vault contains duplicate tool ids"
        }
        return states
    }

    private fun writeToolStateLocked(state: GeneratedToolPersistentState) {
        ensureDirectories()
        val plaintext = GeneratedToolStateCodec.encode(listOf(state))
        require(plaintext.size <= MAX_TOOL_PLAINTEXT_BYTES) {
            "Generated-tool segment payload too large"
        }
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_TOOL_PLAINTEXT_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(
            fileForToolId(state.record.manifest.toolId),
            container,
        )
    }

    private fun ensureIndexedLocked(toolId: String) {
        val current = readIndexLocked().toMutableSet()
        if (current.add(toolId)) writeIndexLocked(current.toSortedSet())
    }

    private fun reconcileIndexLocked(discovered: Set<String>) {
        val indexed = readIndexLocked()
        if (indexed != discovered) writeIndexLocked(discovered.toSortedSet())
    }

    private fun readIndexLocked(): Set<String> {
        if (!exists(indexFile)) return emptySet()
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(indexFile, MAX_INDEX_PLAINTEXT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_INDEX_PLAINTEXT_BYTES,
        )
        return DataInputStream(ByteArrayInputStream(plaintext)).use { data ->
            require(data.readInt() == INDEX_VERSION) { "Unsupported generated-tool index version" }
            val count = data.readInt()
            require(count in 0..MAX_INDEX_ENTRIES) { "Invalid generated-tool index size" }
            buildSet {
                repeat(count) {
                    val length = data.readInt()
                    require(length in 1..MAX_TOOL_ID_BYTES) { "Invalid generated-tool id length" }
                    val bytes = ByteArray(length).also(data::readFully)
                    val toolId = bytes.toString(Charsets.UTF_8)
                    require(toolId.isNotBlank()) { "Blank generated-tool index id" }
                    require(add(toolId)) { "Duplicate generated-tool index id" }
                }
                require(data.available() == 0) { "Trailing generated-tool index bytes" }
            }
        }
    }

    private fun writeIndexLocked(toolIds: Set<String>) {
        require(toolIds.size <= MAX_INDEX_ENTRIES) { "Generated-tool index exceeds size limit" }
        val plaintext = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(INDEX_VERSION)
                data.writeInt(toolIds.size)
                toolIds.sorted().forEach { toolId ->
                    val bytes = toolId.toByteArray(Charsets.UTF_8)
                    require(bytes.size in 1..MAX_TOOL_ID_BYTES) { "Generated-tool id too large" }
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
            output.toByteArray()
        }
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_INDEX_PLAINTEXT_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(indexFile, container)
    }

    private fun requireLifecycleAppend(
        existing: GeneratedToolPersistentState?,
        record: GeneratedToolRecord,
        auditEntries: List<GeneratedToolAuditEntry>,
    ) {
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
    }

    private fun readLegacyStatesLocked(): List<GeneratedToolPersistentState> {
        val container = legacyTarget.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= LEGACY_MAX_CONTAINER_BYTES) {
                    "Legacy generated-tool state vault file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decryptLegacy(container)
    }

    private fun decryptLegacy(container: ByteArray): List<GeneratedToolPersistentState> {
        require(container.size <= LEGACY_MAX_CONTAINER_BYTES) {
            "Legacy generated-tool state vault file too large"
        }
        return DataInputStream(ByteArrayInputStream(container)).use { data ->
            require(data.readInt() == LEGACY_CONTAINER_VERSION) {
                "Unsupported legacy generated-tool state container"
            }
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
                cipher.doFinal(encrypted),
                expectedVersion = storedCodecVersion,
            )
        }
    }

    private fun fileForToolId(toolId: String): File {
        require(toolId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toolId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return toolsDirectory.resolve("tool-$digest$TOOL_SEGMENT_SUFFIX")
    }

    private fun segmentBaseFiles(): List<File> {
        val files = toolsDirectory.listFiles().orEmpty()
        return files.mapNotNull { file ->
            when {
                file.name.endsWith(TOOL_SEGMENT_SUFFIX) -> file
                file.name.endsWith("$TOOL_SEGMENT_SUFFIX.bak") ->
                    File(file.parentFile, file.name.removeSuffix(".bak"))
                else -> null
            }
        }.distinctBy { it.path }.sortedBy { it.name }
    }

    private fun ensureDirectories() {
        check(directory.isDirectory || directory.mkdirs()) { "Generated-tool state vault unavailable" }
        check(toolsDirectory.isDirectory || toolsDirectory.mkdirs()) {
            "Generated-tool state segment directory unavailable"
        }
    }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock { block() }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private companion object {
        val processMutex = Mutex()
        const val ROOT_DIRECTORY = "generated-tool-state-vault"
        const val TOOLS_DIRECTORY = "tools"
        const val INDEX_FILE_NAME = "index.tools"
        const val LEGACY_VAULT_FILE_NAME = "state.tools"
        const val TOOL_SEGMENT_SUFFIX = ".toolstate"
        const val KEY_ALIAS = "lifeos.generated-tools.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val INDEX_VERSION = 1
        const val MAX_INDEX_ENTRIES = 50_000
        const val MAX_TOOL_ID_BYTES = 4 * 1024
        const val MAX_INDEX_PLAINTEXT_BYTES = 4 * 1024 * 1024
        const val MAX_TOOL_PLAINTEXT_BYTES = 16 * 1024 * 1024

        const val LEGACY_CONTAINER_VERSION = 1
        const val LEGACY_MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024
        const val LEGACY_MAX_CONTAINER_BYTES = LEGACY_MAX_PLAINTEXT_BYTES + 64 * 1024
    }
}
