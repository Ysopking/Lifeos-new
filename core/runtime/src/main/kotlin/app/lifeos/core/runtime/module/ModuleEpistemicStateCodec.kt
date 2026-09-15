package app.lifeos.core.runtime.module

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.runtime.informationasset.InformationAssetId
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Bounded deterministic binary codec for durable module epistemic state. */
object ModuleEpistemicStateCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 1_048_576
    private const val MAGIC = 0x4d455031 // MEP1
    private const val MAX_STRING_BYTES = 65_536
    private const val MAX_CAPABILITIES = 1_024
    private const val MAX_DOMAINS = 1_024
    private const val MAX_BINDINGS_PER_DOMAIN = 4_096
    private const val MAX_EVIDENCE_FINGERPRINTS = 4_096

    fun encode(state: ModuleEpistemicState): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(FORMAT_VERSION)
            writeIdentity(stream, state.identity)
            stream.writeLong(state.revision)
            stream.writeBoolean(state.previousStateFingerprint != null)
            state.previousStateFingerprint?.let { writeString(stream, it) }
            writeAuthority(stream, state.authority)
            stream.writeDouble(state.currentConfidence)
            writeResourceProfile(stream, state.resourceProfile)
            writeString(stream, state.updatedAt.toString())

            stream.writeInt(state.knowledge.size)
            state.knowledge.forEach { writeKnowledge(stream, it) }
            stream.writeInt(state.expertise.size)
            state.expertise.forEach { writeExpertise(stream, it) }
            writeString(stream, state.stateFingerprint)
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "Module epistemic payload size invalid"
            }
        }
    }

    fun decode(payload: ByteArray): ModuleEpistemicState {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Module epistemic payload size invalid"
        }
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Invalid module epistemic payload magic" }
        require(input.readInt() == FORMAT_VERSION) { "Unsupported module epistemic payload version" }
        val identity = readIdentity(input)
        val revision = input.readLong()
        val previous = if (input.readBoolean()) readString(input) else null
        val authority = readAuthority(input)
        val currentConfidence = input.readDouble()
        val resourceProfile = readResourceProfile(input)
        val updatedAt = Instant.parse(readString(input))

        val knowledgeCount = readCount(input, MAX_DOMAINS, "knowledge domains")
        val knowledge = List(knowledgeCount) { readKnowledge(input) }.sortedBy { it.domainId.value }
        val expertiseCount = readCount(input, MAX_DOMAINS, "expertise domains")
        val expertise = List(expertiseCount) { readExpertise(input) }.sortedBy { it.domainId.value }
        val expectedFingerprint = readString(input)
        require(input.available() == 0) { "Trailing bytes in module epistemic payload" }

        val state = ModuleEpistemicState(
            identity = identity,
            revision = revision,
            previousStateFingerprint = previous,
            authority = authority,
            knowledge = knowledge,
            expertise = expertise,
            currentConfidence = currentConfidence,
            resourceProfile = resourceProfile,
            updatedAt = updatedAt,
        )
        require(state.stateFingerprint == expectedFingerprint) {
            "Module epistemic state fingerprint mismatch"
        }
        return state
    }

    private fun writeIdentity(stream: DataOutputStream, identity: ModuleIdentity) {
        writeString(stream, identity.moduleId)
        writeString(stream, identity.version)
        writeString(stream, identity.implementationHash)
        writeStrings(stream, identity.capabilityIds.sorted())
    }

    private fun readIdentity(input: DataInputStream): ModuleIdentity = ModuleIdentity(
        moduleId = readString(input),
        version = readString(input),
        implementationHash = readString(input),
        capabilityIds = readStrings(input, MAX_CAPABILITIES).toSet(),
    )

    private fun writeAuthority(stream: DataOutputStream, value: ModuleAuthorityProfile) {
        stream.writeDouble(value.architecturalFloor)
        stream.writeDouble(value.architecturalBaseline)
        stream.writeDouble(value.architecturalCeiling)
        stream.writeDouble(value.learnedAdjustment)
        stream.writeDouble(value.maxLearnedAdjustment)
        writeStrings(stream, value.learningEvidenceFingerprints.sorted())
    }

    private fun readAuthority(input: DataInputStream): ModuleAuthorityProfile = ModuleAuthorityProfile(
        architecturalFloor = input.readDouble(),
        architecturalBaseline = input.readDouble(),
        architecturalCeiling = input.readDouble(),
        learnedAdjustment = input.readDouble(),
        maxLearnedAdjustment = input.readDouble(),
        learningEvidenceFingerprints = readStrings(input, MAX_EVIDENCE_FINGERPRINTS).toSet(),
    )

    private fun writeResourceProfile(stream: DataOutputStream, value: ModuleResourceProfile) {
        stream.writeDouble(value.cpuIntensity)
        stream.writeDouble(value.memoryIntensity)
        stream.writeDouble(value.ioIntensity)
        stream.writeDouble(value.networkIntensity)
        stream.writeDouble(value.thermalIntensity)
        stream.writeInt(value.maxParallelism)
    }

    private fun readResourceProfile(input: DataInputStream): ModuleResourceProfile = ModuleResourceProfile(
        cpuIntensity = input.readDouble(),
        memoryIntensity = input.readDouble(),
        ioIntensity = input.readDouble(),
        networkIntensity = input.readDouble(),
        thermalIntensity = input.readDouble(),
        maxParallelism = input.readInt(),
    )

    private fun writeKnowledge(stream: DataOutputStream, value: ModuleDomainKnowledgeState) {
        writeString(stream, value.domainId.value)
        stream.writeDouble(value.knowledgeDepth)
        stream.writeDouble(value.evidenceCoverage)
        stream.writeDouble(value.confidence)
        stream.writeInt(value.bindings.size)
        value.bindings.forEach { binding ->
            writeString(stream, binding.domainId.value)
            writeString(stream, binding.semanticKey)
            writeString(stream, binding.assetId.value)
            writeString(stream, binding.assetRevisionId.value)
            writeString(stream, binding.assetStateHash)
            stream.writeDouble(binding.confidence)
            writeString(stream, binding.observedAt.toString())
        }
    }

    private fun readKnowledge(input: DataInputStream): ModuleDomainKnowledgeState {
        val domainId = FieldDomainId(readString(input))
        val knowledgeDepth = input.readDouble()
        val evidenceCoverage = input.readDouble()
        val confidence = input.readDouble()
        val bindingCount = readCount(input, MAX_BINDINGS_PER_DOMAIN, "knowledge bindings")
        val bindings = List(bindingCount) {
            ModuleKnowledgeBinding(
                domainId = FieldDomainId(readString(input)),
                semanticKey = readString(input),
                assetId = InformationAssetId(readString(input)),
                assetRevisionId = InformationAssetRevisionId(readString(input)),
                assetStateHash = readString(input),
                confidence = input.readDouble(),
                observedAt = Instant.parse(readString(input)),
            )
        }.sortedBy { it.stableKey }
        return ModuleDomainKnowledgeState(
            domainId = domainId,
            knowledgeDepth = knowledgeDepth,
            evidenceCoverage = evidenceCoverage,
            confidence = confidence,
            bindings = bindings,
        )
    }

    private fun writeExpertise(stream: DataOutputStream, value: ModuleExpertiseState) {
        writeString(stream, value.domainId.value)
        stream.writeDouble(value.baselineExpertise)
        stream.writeDouble(value.learnedAdjustment)
        stream.writeDouble(value.maxLearnedAdjustment)
        stream.writeDouble(value.calibration)
        stream.writeLong(value.observedOutcomes)
        stream.writeDouble(value.successfulOutcomeFraction)
        writeStrings(stream, value.learningEvidenceFingerprints.sorted())
    }

    private fun readExpertise(input: DataInputStream): ModuleExpertiseState = ModuleExpertiseState(
        domainId = FieldDomainId(readString(input)),
        baselineExpertise = input.readDouble(),
        learnedAdjustment = input.readDouble(),
        maxLearnedAdjustment = input.readDouble(),
        calibration = input.readDouble(),
        observedOutcomes = input.readLong(),
        successfulOutcomeFraction = input.readDouble(),
        learningEvidenceFingerprints = readStrings(input, MAX_EVIDENCE_FINGERPRINTS).toSet(),
    )

    private fun writeStrings(stream: DataOutputStream, values: List<String>) {
        stream.writeInt(values.size)
        values.forEach { writeString(stream, it) }
    }

    private fun readStrings(input: DataInputStream, maxCount: Int): List<String> {
        val count = readCount(input, maxCount, "strings")
        return List(count) { readString(input) }
    }

    private fun writeString(stream: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Module epistemic string too large" }
        stream.writeInt(bytes.size)
        stream.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Malformed module epistemic string length"
        }
        val bytes = ByteArray(length).also(input::readFully)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readCount(input: DataInputStream, maxCount: Int, label: String): Int {
        val count = input.readInt()
        require(count in 0..maxCount) { "Invalid module epistemic $label count" }
        return count
    }
}
