package app.lifeos.core.runtime.capability

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

object ToolWorkshopStagePayloadCodec {
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 256 * 1024
    private const val MAX_COLLECTION = 1_024

    fun encode(value: ToolSpecification): String = pack("SPEC") { out ->
        write(out, value.purpose)
        writeRequirement(out, value.requiredCapability)
        writeEnumSet(out, value.allowedPermissions)
        writeStrings(out, value.forbiddenSideEffects.sorted())
        out.writeLong(value.maxSourceBytes)
    }

    fun decodeSpecification(payload: String): ToolSpecification = unpack(payload, "SPEC") { input ->
        ToolSpecification(
            purpose = read(input),
            requiredCapability = readRequirement(input),
            allowedPermissions = readEnumSet<ToolPermission>(input),
            forbiddenSideEffects = readStrings(input).toSet(),
            maxSourceBytes = input.readLong(),
        )
    }

    fun encode(value: ToolDesign): String = pack("DESIGN") { out ->
        write(out, value.toolId)
        write(out, encode(value.specification))
        write(out, value.implementationNotes)
    }

    fun decodeDesign(payload: String): ToolDesign = unpack(payload, "DESIGN") { input ->
        ToolDesign(
            toolId = read(input),
            specification = decodeSpecification(read(input)),
            implementationNotes = read(input),
        )
    }

    fun encode(value: GeneratedSource): String = pack("SOURCE") { out ->
        write(out, value.toolId)
        write(out, value.source)
    }

    fun decodeSource(payload: String): GeneratedSource = unpack(payload, "SOURCE") { input ->
        GeneratedSource(toolId = read(input), source = read(input))
    }

    fun encode(value: ToolBuildResult): String = pack("BUILD") { out ->
        write(out, value.toolId)
        writeNullable(out, value.artifactRef)
        write(out, value.sourceHash)
        writeNullable(out, value.buildHash)
        out.writeBoolean(value.success)
        writeStrings(out, value.diagnostics)
    }

    fun decodeBuild(payload: String): ToolBuildResult = unpack(payload, "BUILD") { input ->
        ToolBuildResult(
            toolId = read(input),
            artifactRef = readNullable(input),
            sourceHash = read(input),
            buildHash = readNullable(input),
            success = input.readBoolean(),
            diagnostics = readStrings(input),
        )
    }

    fun encode(value: ToolTestResult): String = pack("TEST") { out ->
        out.writeBoolean(value.success)
        out.writeInt(value.passed)
        out.writeInt(value.failed)
        writeStrings(out, value.diagnostics)
    }

    fun decodeTest(payload: String): ToolTestResult = unpack(payload, "TEST") { input ->
        ToolTestResult(
            success = input.readBoolean(),
            passed = input.readInt(),
            failed = input.readInt(),
            diagnostics = readStrings(input),
        )
    }

    fun encode(value: ToolSecurityResult): String = pack("SECURITY") { out ->
        out.writeBoolean(value.accepted)
        writeStrings(out, value.violations)
    }

    fun decodeSecurity(payload: String): ToolSecurityResult = unpack(payload, "SECURITY") { input ->
        ToolSecurityResult(
            accepted = input.readBoolean(),
            violations = readStrings(input),
        )
    }

    fun encode(value: CapabilityVerificationResult): String = pack("VERIFY") { out ->
        out.writeBoolean(value.verified)
        out.writeDouble(value.confidence)
        writeStrings(out, value.diagnostics)
    }

    fun decodeVerification(payload: String): CapabilityVerificationResult = unpack(payload, "VERIFY") { input ->
        CapabilityVerificationResult(
            verified = input.readBoolean(),
            confidence = input.readDouble(),
            diagnostics = readStrings(input),
        )
    }

    private fun writeRequirement(out: DataOutputStream, value: CapabilityRequirement) {
        write(out, value.capabilityId.value)
        write(out, value.severity.name)
        writeStrings(out, value.requiredInputs.sorted())
        writeStrings(out, value.requiredOutputs.sorted())
    }

    private fun readRequirement(input: DataInputStream): CapabilityRequirement = CapabilityRequirement(
        capabilityId = CapabilityId(read(input)),
        severity = enumValueOf(read(input)),
        requiredInputs = readStrings(input).toSet(),
        requiredOutputs = readStrings(input).toSet(),
    )

    private inline fun <reified T : Enum<T>> writeEnumSet(out: DataOutputStream, values: Set<T>) {
        writeStrings(out, values.map { it.name }.sorted())
    }

    private inline fun <reified T : Enum<T>> readEnumSet(input: DataInputStream): Set<T> =
        readStrings(input).map { enumValueOf<T>(it) }.toSet()

    private fun pack(kind: String, writer: (DataOutputStream) -> Unit): String {
        val bytes = ByteArrayOutputStream().let { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(VERSION)
                write(out, kind)
                writer(out)
            }
            buffer.toByteArray()
        }
        require(bytes.size <= ToolWorkshopStageArtifact.MAX_PAYLOAD_BYTES)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun <T> unpack(payload: String, expectedKind: String, reader: (DataInputStream) -> T): T {
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.isNotEmpty() && bytes.size <= ToolWorkshopStageArtifact.MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == VERSION) { "Unsupported workshop stage payload version" }
        require(read(input) == expectedKind) { "Workshop stage payload kind mismatch" }
        val value = reader(input)
        require(input.available() == 0) { "Trailing workshop stage payload bytes" }
        return value
    }

    private fun writeStrings(out: DataOutputStream, values: List<String>) {
        require(values.size <= MAX_COLLECTION)
        out.writeInt(values.size)
        values.forEach { write(out, it) }
    }

    private fun readStrings(input: DataInputStream): List<String> {
        val count = input.readInt()
        require(count in 0..MAX_COLLECTION)
        return List(count) { read(input) }
    }

    private fun writeNullable(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) write(out, value)
    }

    private fun readNullable(input: DataInputStream): String? = if (input.readBoolean()) read(input) else null

    private fun write(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun read(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available())
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
