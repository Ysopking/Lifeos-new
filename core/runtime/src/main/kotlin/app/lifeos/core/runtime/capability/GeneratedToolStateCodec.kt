package app.lifeos.core.runtime.capability

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Deterministic, Android-free codec for the complete generated-tool lifecycle snapshot. */
object GeneratedToolStateCodec {
    const val VERSION = 2
    const val LEGACY_VERSION = 1

    private const val MAGIC = 0x4C475453 // LGTS
    private const val MAX_TOOLS = 2_048
    private const val MAX_AUDIT_ENTRIES = 20_000
    private const val MAX_TRIAL_RESULTS = 20_000
    private const val MAX_LIST_ENTRIES = 20_000
    private const val MAX_STRING_BYTES = 32 * 1024
    private val SUPPORTED_VERSIONS = setOf(LEGACY_VERSION, VERSION)

    fun supportsVersion(version: Int): Boolean = version in SUPPORTED_VERSIONS

    fun encode(states: List<GeneratedToolPersistentState>): ByteArray {
        require(states.size <= MAX_TOOLS) { "Too many generated-tool states" }
        require(states.map { it.record.manifest.toolId }.distinct().size == states.size) {
            "Generated-tool snapshot contains duplicate tool identities"
        }
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                val ordered = states.sortedBy { it.record.manifest.toolId }
                data.writeInt(ordered.size)
                ordered.forEach { data.writeStateV2(it) }
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray, expectedVersion: Int? = null): List<GeneratedToolPersistentState> {
        require(bytes.isNotEmpty()) { "Generated-tool state payload is empty" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid generated-tool state magic" }
            val storedVersion = data.readInt()
            require(supportsVersion(storedVersion)) { "Unsupported generated-tool state codec" }
            require(expectedVersion == null || storedVersion == expectedVersion) {
                "Generated-tool state payload version does not match vault metadata"
            }
            val count = data.readBoundedCount(MAX_TOOLS, "tools")
            val states = List(count) {
                when (storedVersion) {
                    LEGACY_VERSION -> data.readStateV1()
                    VERSION -> data.readStateV2()
                    else -> error("Unsupported generated-tool state codec")
                }
            }
            require(states.map { it.record.manifest.toolId }.distinct().size == states.size) {
                "Generated-tool state payload contains duplicate tool identities"
            }
            require(data.read() == -1) { "Generated-tool state payload contains trailing bytes" }
            states.sortedBy { it.record.manifest.toolId }
        }
    }

    private fun DataOutputStream.writeStateV2(state: GeneratedToolPersistentState) {
        writeText(state.id)
        writeRecord(state.record)
        require(state.auditEntries.size <= MAX_AUDIT_ENTRIES)
        writeInt(state.auditEntries.size)
        state.auditEntries.forEach { writeAudit(it) }
        writeTrialEvidence(state.trialEvidence)
        val kind = when {
            state.promotionReceipt != null -> ReceiptKind.J03_APK
            state.boundedPromotionReceipt != null -> ReceiptKind.BOUNDED
            else -> ReceiptKind.NONE
        }
        writeText(kind.name)
        when (kind) {
            ReceiptKind.NONE -> Unit
            ReceiptKind.J03_APK -> writePromotionReceipt(requireNotNull(state.promotionReceipt))
            ReceiptKind.BOUNDED -> writeBoundedPromotionReceipt(requireNotNull(state.boundedPromotionReceipt))
        }
    }

    /** Exact pre-V1.2 state layout: boolean receipt flag followed by the J03/APK receipt only. */
    private fun DataInputStream.readStateV1(): GeneratedToolPersistentState {
        val expectedId = readText()
        val record = readRecord()
        val audit = List(readBoundedCount(MAX_AUDIT_ENTRIES, "audit entries")) { readAudit() }
        val trials = readTrialEvidence()
        val receipt = if (readBoolean()) readPromotionReceipt() else null
        return GeneratedToolPersistentState(
            record = record,
            auditEntries = audit,
            trialEvidence = trials,
            promotionReceipt = receipt,
        ).also { state ->
            require(state.id == expectedId) { "Generated-tool persistent state id integrity check failed" }
        }
    }

    private fun DataInputStream.readStateV2(): GeneratedToolPersistentState {
        val expectedId = readText()
        val record = readRecord()
        val audit = List(readBoundedCount(MAX_AUDIT_ENTRIES, "audit entries")) { readAudit() }
        val trials = readTrialEvidence()
        val kind = runCatching { enumValueOf<ReceiptKind>(readText()) }
            .getOrElse { throw IllegalArgumentException("Unsupported generated-tool receipt kind", it) }
        val legacyReceipt: GeneratedToolPromotionReceipt?
        val boundedReceipt: BoundedGeneratedToolPromotionReceipt?
        when (kind) {
            ReceiptKind.NONE -> {
                legacyReceipt = null
                boundedReceipt = null
            }
            ReceiptKind.J03_APK -> {
                legacyReceipt = readPromotionReceipt()
                boundedReceipt = null
            }
            ReceiptKind.BOUNDED -> {
                legacyReceipt = null
                boundedReceipt = readBoundedPromotionReceipt()
            }
        }
        return GeneratedToolPersistentState(
            record = record,
            auditEntries = audit,
            trialEvidence = trials,
            promotionReceipt = legacyReceipt,
            boundedPromotionReceipt = boundedReceipt,
        ).also { state ->
            require(state.id == expectedId) { "Generated-tool persistent state id integrity check failed" }
        }
    }

    private fun DataOutputStream.writeRecord(record: GeneratedToolRecord) {
        val manifest = record.manifest
        writeText(manifest.toolId)
        writeText(manifest.sourceCapability.value)
        writeText(manifest.sourceHash)
        writeNullableText(manifest.buildHash)
        writeEnumNames(manifest.permissions.map { it.name })
        writeInstant(manifest.generatedAt)
        writeTexts(manifest.requiredInputs.sorted())
        writeTexts(manifest.requiredOutputs.sorted())
        writeText(record.state.name)
        writeDouble(record.verificationConfidence)
        writeNullableText(record.lastMessage)
        writeNullableText(record.promotionEvidenceId)
    }

    private fun DataInputStream.readRecord(): GeneratedToolRecord {
        val toolId = readText()
        val capability = CapabilityId(readText())
        val sourceHash = readText()
        val buildHash = readNullableText()
        val permissions = readEnumNames("permissions").mapTo(linkedSetOf()) { enumValueOf<ToolPermission>(it) }
        val generatedAt = readInstant()
        val requiredInputs = readTexts("required inputs").toSet()
        val requiredOutputs = readTexts("required outputs").toSet()
        return GeneratedToolRecord(
            manifest = GeneratedToolManifest(
                toolId = toolId,
                sourceCapability = capability,
                sourceHash = sourceHash,
                buildHash = buildHash,
                permissions = permissions,
                generatedAt = generatedAt,
                requiredInputs = requiredInputs,
                requiredOutputs = requiredOutputs,
            ),
            state = enumValueOf(readText()),
            verificationConfidence = readDouble(),
            lastMessage = readNullableText(),
            promotionEvidenceId = readNullableText(),
        )
    }

    private fun DataOutputStream.writeAudit(entry: GeneratedToolAuditEntry) {
        writeText(entry.id)
        writeText(entry.toolId)
        writeText(entry.action.name)
        writeNullableText(entry.fromState?.name)
        writeText(entry.toState.name)
        writeNullableText(entry.beforeRecordFingerprint)
        writeText(entry.afterRecordFingerprint)
        writeNullableText(entry.actorId)
        writeNullableText(entry.evidenceRef)
        writeNullableText(entry.reason)
        writeInstant(entry.occurredAt)
        writeNullableText(entry.previousEntryId)
    }

    private fun DataInputStream.readAudit(): GeneratedToolAuditEntry {
        val expectedId = readText()
        val entry = GeneratedToolAuditEntry(
            toolId = readText(),
            action = enumValueOf(readText()),
            fromState = readNullableText()?.let { enumValueOf<GeneratedToolState>(it) },
            toState = enumValueOf(readText()),
            beforeRecordFingerprint = readNullableText(),
            afterRecordFingerprint = readText(),
            actorId = readNullableText(),
            evidenceRef = readNullableText(),
            reason = readNullableText(),
            occurredAt = readInstant(),
            previousEntryId = readNullableText(),
        )
        require(entry.id == expectedId) { "Generated-tool audit id integrity check failed" }
        return entry
    }

    private fun DataOutputStream.writeTrialEvidence(evidence: GeneratedToolTrialEvidence) {
        writeText(evidence.id)
        writeText(evidence.toolId)
        require(evidence.orderedResults.size <= MAX_TRIAL_RESULTS)
        writeInt(evidence.orderedResults.size)
        evidence.orderedResults.forEach { result ->
            writeText(result.fingerprint())
            writeText(result.invocationId)
            writeBoolean(result.success)
            writeBoolean(result.producedExpectedOutput)
            writeBoolean(result.safetyViolation)
            writeLong(result.latencyMs)
            writeInstant(result.recordedAt)
        }
    }

    private fun DataInputStream.readTrialEvidence(): GeneratedToolTrialEvidence {
        val expectedEvidenceId = readText()
        val toolId = readText()
        val results = List(readBoundedCount(MAX_TRIAL_RESULTS, "trial results")) {
            val expectedResultFingerprint = readText()
            val result = GeneratedToolTrialResult(
                invocationId = readText(),
                success = readBoolean(),
                producedExpectedOutput = readBoolean(),
                safetyViolation = readBoolean(),
                latencyMs = readLong(),
                recordedAt = readInstant(),
            )
            require(result.fingerprint() == expectedResultFingerprint) {
                "Generated-tool trial result fingerprint integrity check failed"
            }
            result
        }
        val evidence = GeneratedToolTrialEvidence(toolId, results)
        require(evidence.id == expectedEvidenceId) {
            "Generated-tool trial evidence id integrity check failed"
        }
        return evidence
    }

    private fun DataOutputStream.writePromotionReceipt(receipt: GeneratedToolPromotionReceipt) {
        writeText(receipt.id)
        writeText(receipt.evidenceId)
        writeText(receipt.toolId)
        writeText(receipt.candidateArtifactId)
        writeText(receipt.candidateId)
        writeText(receipt.verificationId)
        writeText(receipt.provenanceId)
        writeText(receipt.recordFingerprint)
        writeText(receipt.trialEvidenceId)
        writeText(receipt.promotionPolicyFingerprint)
        writeText(receipt.apkSha256)
        writeText(receipt.capabilityChangeFingerprint)
        writeText(receipt.permissionDeltaFingerprint)
        writeTexts(receipt.reviewerEvidenceFingerprints.sorted())
        writeTexts(receipt.promotionActorEvidenceFingerprints.sorted())
    }

    private fun DataInputStream.readPromotionReceipt(): GeneratedToolPromotionReceipt {
        val expectedReceiptId = readText()
        val receipt = GeneratedToolPromotionReceipt(
            evidenceId = readText(),
            toolId = readText(),
            candidateArtifactId = readText(),
            candidateId = readText(),
            verificationId = readText(),
            provenanceId = readText(),
            recordFingerprint = readText(),
            trialEvidenceId = readText(),
            promotionPolicyFingerprint = readText(),
            apkSha256 = readText(),
            capabilityChangeFingerprint = readText(),
            permissionDeltaFingerprint = readText(),
            reviewerEvidenceFingerprints = readTexts("reviewer evidence", requireUnique = false),
            promotionActorEvidenceFingerprints = readTexts("promotion actor evidence", requireUnique = false),
        )
        require(receipt.id == expectedReceiptId) {
            "Generated-tool promotion receipt id integrity check failed"
        }
        return receipt
    }

    private fun DataOutputStream.writeBoundedPromotionReceipt(receipt: BoundedGeneratedToolPromotionReceipt) {
        writeText(receipt.id)
        writeText(receipt.evidenceId)
        writeText(receipt.toolId)
        writeText(receipt.artifactId)
        writeText(receipt.recordFingerprint)
        writeText(receipt.trialEvidenceId)
        writeText(receipt.promotionPolicyFingerprint)
        writeText(receipt.novelAdmissionEvidenceId)
        writeText(receipt.canaryReadinessEvidenceId)
        writeText(receipt.promotionSealId)
        writeTexts(receipt.reviewerEvidenceFingerprints.sorted())
        writeTexts(receipt.activationActorEvidenceFingerprints.sorted())
    }

    private fun DataInputStream.readBoundedPromotionReceipt(): BoundedGeneratedToolPromotionReceipt {
        val expectedReceiptId = readText()
        val receipt = BoundedGeneratedToolPromotionReceipt(
            evidenceId = readText(),
            toolId = readText(),
            artifactId = readText(),
            recordFingerprint = readText(),
            trialEvidenceId = readText(),
            promotionPolicyFingerprint = readText(),
            novelAdmissionEvidenceId = readText(),
            canaryReadinessEvidenceId = readText(),
            promotionSealId = readText(),
            reviewerEvidenceFingerprints = readTexts("bounded reviewer evidence", requireUnique = false),
            activationActorEvidenceFingerprints = readTexts("bounded activation actor evidence", requireUnique = false),
        )
        require(receipt.id == expectedReceiptId) {
            "Bounded generated-tool promotion receipt id integrity check failed"
        }
        return receipt
    }

    private fun DataOutputStream.writeEnumNames(values: List<String>) = writeTexts(values.sorted())

    private fun DataInputStream.readEnumNames(label: String): List<String> = readTexts(label)

    private fun DataOutputStream.writeTexts(values: List<String>) {
        require(values.size <= MAX_LIST_ENTRIES) { "Generated-tool list exceeds size limit" }
        writeInt(values.size)
        values.forEach { writeText(it) }
    }

    private fun DataInputStream.readTexts(label: String, requireUnique: Boolean = true): List<String> {
        val values = List(readBoundedCount(MAX_LIST_ENTRIES, label)) { readText() }
        if (requireUnique) {
            require(values.distinct().size == values.size) { "Generated-tool $label contains duplicates" }
        }
        return values
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
        require(bytes.size <= MAX_STRING_BYTES) { "Generated-tool string exceeds size limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid generated-tool string length" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataInputStream.readBoundedCount(maximum: Int, label: String): Int = readInt().also {
        require(it in 0..maximum) { "Invalid generated-tool $label count: $it" }
    }

    private enum class ReceiptKind {
        NONE,
        J03_APK,
        BOUNDED,
    }
}
