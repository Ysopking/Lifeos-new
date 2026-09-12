package app.lifeos.core.runtime

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.CognitiveBranch
import app.lifeos.core.model.CognitiveBranchStatus
import app.lifeos.core.model.CognitiveIntegrationRecord
import app.lifeos.core.model.CognitiveStateHash
import app.lifeos.core.model.ConvergenceId
import app.lifeos.core.model.DeterminismContext
import app.lifeos.core.model.LogicalTick
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.ModuleProcessingId
import app.lifeos.core.model.ModuleProcessingRecord
import app.lifeos.core.model.PhotonBranchId
import app.lifeos.core.model.PhotonId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Deterministic lossless codec for the durable high-resolution causal ledger. */
internal object CausalLedgerCodec {
    private const val VERSION = 2

    fun encode(entry: CausalLedgerEntry): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.string(entry.traceId.value)
            out.string(entry.rootPhotonId.value)

            out.writeInt(entry.attraction.size)
            entry.attraction.forEach { record ->
                out.string(record.moduleFingerprint)
                out.string(record.moduleId)
                out.string(record.moduleVersion)
                out.writeDouble(record.score)
                out.writeBoolean(record.selected)
                out.strings(record.reasons)
            }

            out.writeInt(entry.branches.size)
            entry.branches.forEach { branch ->
                out.string(branch.branchId.value)
                out.string(branch.traceId.value)
                out.string(branch.parentPhotonId.value)
                out.writeLong(branch.parentRevision)
                out.writeInt(branch.ordinal)
                out.module(branch.module)
                out.string(branch.inputStateHash.value)
                out.string(branch.status.name)
                out.strings(branch.outputPhotonIds.map { it.value })
                out.nullableString(branch.outputStateHash?.value)
            }

            out.writeInt(entry.processingRecords.size)
            entry.processingRecords.forEach { record ->
                out.string(record.processingId.value)
                out.string(record.traceId.value)
                out.string(record.branchId.value)
                out.module(record.module)
                out.string(record.inputPhotonId.value)
                out.writeLong(record.inputRevision)
                out.context(record.context)
                out.strings(record.outputPhotonIds.map { it.value })
                out.string(record.outputStateHash.value)
            }

            out.writeBoolean(entry.integration != null)
            entry.integration?.let { integration ->
                out.string(integration.convergenceId.value)
                out.string(integration.traceId.value)
                out.strings(integration.branchIds.map { it.value })
                out.strings(integration.contributingBranchIds.map { it.value })
                out.string(integration.inputStateHash.value)
                out.string(integration.outputStateHash.value)
                out.string(integration.integratedPhotonId.value)
                out.string(integration.reasonFingerprint)
            }
            out.strings(entry.emittedPhotonIds.map { it.value })
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    fun decode(payload: String): CausalLedgerEntry {
        require(payload.isNotBlank()) { "Missing causal ledger payload" }
        val bytes = Base64.getUrlDecoder().decode(payload)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported causal ledger binary version" }
            val traceId = CausalTraceId(input.string())
            val root = PhotonId(input.string())

            val attraction = List(input.count()) {
                ModuleAttractionLedgerRecord(
                    moduleFingerprint = input.string(),
                    moduleId = input.string(),
                    moduleVersion = input.string(),
                    score = input.readDouble(),
                    selected = input.readBoolean(),
                    reasons = input.strings(),
                )
            }

            val branches = List(input.count()) {
                CognitiveBranch(
                    branchId = PhotonBranchId(input.string()),
                    traceId = CausalTraceId(input.string()),
                    parentPhotonId = PhotonId(input.string()),
                    parentRevision = input.readLong(),
                    ordinal = input.readInt(),
                    module = input.module(),
                    inputStateHash = CognitiveStateHash(input.string()),
                    status = CognitiveBranchStatus.valueOf(input.string()),
                    outputPhotonIds = input.strings().map(::PhotonId),
                    outputStateHash = input.nullableString()?.let(::CognitiveStateHash),
                )
            }

            val processing = List(input.count()) {
                ModuleProcessingRecord(
                    processingId = ModuleProcessingId(input.string()),
                    traceId = CausalTraceId(input.string()),
                    branchId = PhotonBranchId(input.string()),
                    module = input.module(),
                    inputPhotonId = PhotonId(input.string()),
                    inputRevision = input.readLong(),
                    context = input.context(),
                    outputPhotonIds = input.strings().map(::PhotonId),
                    outputStateHash = CognitiveStateHash(input.string()),
                )
            }

            val integration = if (input.readBoolean()) {
                CognitiveIntegrationRecord(
                    convergenceId = ConvergenceId(input.string()),
                    traceId = CausalTraceId(input.string()),
                    branchIds = input.strings().map(::PhotonBranchId),
                    contributingBranchIds = input.strings().map(::PhotonBranchId),
                    inputStateHash = CognitiveStateHash(input.string()),
                    outputStateHash = CognitiveStateHash(input.string()),
                    integratedPhotonId = PhotonId(input.string()),
                    reasonFingerprint = input.string(),
                )
            } else null
            val emitted = input.strings().map(::PhotonId)
            require(input.available() == 0) { "Trailing causal ledger payload data" }
            CausalLedgerEntry(traceId, root, attraction, branches, processing, integration, emitted)
        }
    }

    private fun DataOutputStream.module(value: ModuleIdentity) {
        string(value.moduleId)
        string(value.version)
        string(value.implementationHash)
        strings(value.capabilityIds.sorted())
    }

    private fun DataInputStream.module(): ModuleIdentity = ModuleIdentity(
        moduleId = string(),
        version = string(),
        implementationHash = string(),
        capabilityIds = strings().toSet(),
    )

    private fun DataOutputStream.context(value: DeterminismContext) {
        string(value.traceId.value)
        writeLong(value.logicalTick.value)
        string(value.inputHash.value)
        string(value.parentStateHash.value)
        string(value.runtimeVersion)
        string(value.policyVersion)
        writeLong(value.randomSeed)
        string(value.parametersHash.value)
    }

    private fun DataInputStream.context(): DeterminismContext = DeterminismContext(
        traceId = CausalTraceId(string()),
        logicalTick = LogicalTick(readLong()),
        inputHash = CognitiveStateHash(string()),
        parentStateHash = CognitiveStateHash(string()),
        runtimeVersion = string(),
        policyVersion = string(),
        randomSeed = readLong(),
        parametersHash = CognitiveStateHash(string()),
    )

    private fun DataOutputStream.strings(values: Collection<String>) {
        writeInt(values.size)
        values.forEach(::string)
    }

    private fun DataInputStream.strings(): List<String> = List(count()) { string() }

    private fun DataOutputStream.nullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) string(value)
    }

    private fun DataInputStream.nullableString(): String? = if (readBoolean()) string() else null

    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.string(): String {
        val size = count(max = 16 * 1024 * 1024)
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataInputStream.count(max: Int = 1_000_000): Int {
        val value = readInt()
        require(value in 0..max) { "Invalid causal ledger collection/string size: $value" }
        return value
    }
}
