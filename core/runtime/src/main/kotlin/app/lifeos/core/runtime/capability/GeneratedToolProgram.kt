package app.lifeos.core.runtime.capability

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

enum class GeneratedToolOpcode {
    TRIM,
    NORMALIZE_WHITESPACE,
    UPPERCASE,
    LOWERCASE,
    UNSUPPORTED,
}

data class GeneratedToolInstruction(
    val opcode: GeneratedToolOpcode,
    val argument: String? = null,
) {
    init {
        require(argument == null || argument.toByteArray(StandardCharsets.UTF_8).size <= MAX_ARGUMENT_BYTES) {
            "Generated-tool instruction argument exceeds size limit"
        }
        require(opcode == GeneratedToolOpcode.UNSUPPORTED || argument == null) {
            "Current bounded opcodes do not accept arguments"
        }
    }

    companion object {
        const val MAX_ARGUMENT_BYTES = 1_024
    }
}

data class GeneratedToolProgram(
    val toolId: String,
    val capabilityId: CapabilityId,
    val requiredInputs: Set<String>,
    val requiredOutputs: Set<String>,
    val instructions: List<GeneratedToolInstruction>,
) {
    init {
        require(toolId.isNotBlank()) { "Generated-tool program id must not be blank" }
        require(requiredInputs.none { it.isBlank() }) { "Generated-tool program inputs must not be blank" }
        require(requiredOutputs.none { it.isBlank() }) { "Generated-tool program outputs must not be blank" }
        require(instructions.isNotEmpty()) { "Generated-tool program requires instructions" }
        require(instructions.size <= MAX_INSTRUCTIONS) { "Generated-tool program exceeds instruction budget" }
    }

    val executable: Boolean get() = instructions.none { it.opcode == GeneratedToolOpcode.UNSUPPORTED }

    companion object {
        const val MAX_INSTRUCTIONS = 16
    }
}

object GeneratedToolProgramCodec {
    private const val HEADER = "LIFEOS_GENERATED_TOOL_PROGRAM_V1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(program: GeneratedToolProgram): String = buildString {
        appendLine(HEADER)
        append("tool=").appendLine(encodeField(program.toolId))
        append("capability=").appendLine(encodeField(program.capabilityId.value))
        program.requiredInputs.sorted().forEach { append("input=").appendLine(encodeField(it)) }
        program.requiredOutputs.sorted().forEach { append("output=").appendLine(encodeField(it)) }
        program.instructions.forEach { instruction ->
            append("op=").append(instruction.opcode.name).append('|')
                .appendLine(instruction.argument?.let(::encodeField).orEmpty())
        }
    }.trimEnd()

    fun decode(source: String): GeneratedToolProgram {
        val lines = source.lineSequence().toList()
        require(lines.firstOrNull() == HEADER) { "Generated-tool program header is invalid" }
        require(lines.size <= MAX_LINES) { "Generated-tool program exceeds line budget" }

        var toolId: String? = null
        var capabilityId: CapabilityId? = null
        val inputs = linkedSetOf<String>()
        val outputs = linkedSetOf<String>()
        val instructions = mutableListOf<GeneratedToolInstruction>()

        lines.drop(1).forEach { line ->
            when {
                line.startsWith("tool=") -> {
                    require(toolId == null) { "Generated-tool program repeats tool id" }
                    toolId = decodeField(line.removePrefix("tool="))
                }
                line.startsWith("capability=") -> {
                    require(capabilityId == null) { "Generated-tool program repeats capability" }
                    capabilityId = CapabilityId(decodeField(line.removePrefix("capability=")))
                }
                line.startsWith("input=") -> {
                    require(inputs.add(decodeField(line.removePrefix("input=")))) {
                        "Generated-tool program repeats input"
                    }
                }
                line.startsWith("output=") -> {
                    require(outputs.add(decodeField(line.removePrefix("output=")))) {
                        "Generated-tool program repeats output"
                    }
                }
                line.startsWith("op=") -> {
                    val payload = line.removePrefix("op=")
                    val separator = payload.indexOf('|')
                    require(separator > 0) { "Generated-tool instruction encoding is invalid" }
                    val opcode = GeneratedToolOpcode.valueOf(payload.substring(0, separator))
                    val argumentEncoded = payload.substring(separator + 1)
                    instructions += GeneratedToolInstruction(
                        opcode = opcode,
                        argument = argumentEncoded.takeIf { it.isNotEmpty() }?.let(::decodeField),
                    )
                }
                line.isBlank() -> Unit
                else -> error("Unknown generated-tool program field")
            }
        }

        return GeneratedToolProgram(
            toolId = requireNotNull(toolId) { "Generated-tool program is missing tool id" },
            capabilityId = requireNotNull(capabilityId) { "Generated-tool program is missing capability" },
            requiredInputs = inputs,
            requiredOutputs = outputs,
            instructions = instructions,
        )
    }

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String {
        require(value.isNotBlank()) { "Generated-tool program field must not be blank" }
        val decoded = runCatching { decoder.decode(value) }
            .getOrElse { throw IllegalArgumentException("Generated-tool program field is not valid base64", it) }
        val text = decoded.toString(StandardCharsets.UTF_8)
        require(text.isNotBlank()) { "Generated-tool program decoded field must not be blank" }
        return text
    }

    private const val MAX_LINES = 64
}

class GeneratedToolProgramInterpreter(
    private val maxInputBytes: Int = 64 * 1024,
    private val maxOutputBytes: Int = 64 * 1024,
) {
    init {
        require(maxInputBytes > 0 && maxOutputBytes > 0)
    }

    fun execute(program: GeneratedToolProgram, input: String): String {
        require(program.executable) { "Unsupported generated-tool program cannot execute" }
        require(input.toByteArray(StandardCharsets.UTF_8).size <= maxInputBytes) {
            "Generated-tool input exceeds runtime budget"
        }
        var value = input
        program.instructions.forEach { instruction ->
            value = when (instruction.opcode) {
                GeneratedToolOpcode.TRIM -> value.trim()
                GeneratedToolOpcode.NORMALIZE_WHITESPACE -> value.trim().replace(Regex("\\s+"), " ")
                GeneratedToolOpcode.UPPERCASE -> value.uppercase(Locale.ROOT)
                GeneratedToolOpcode.LOWERCASE -> value.lowercase(Locale.ROOT)
                GeneratedToolOpcode.UNSUPPORTED -> error("Unsupported generated-tool opcode")
            }
            require(value.toByteArray(StandardCharsets.UTF_8).size <= maxOutputBytes) {
                "Generated-tool output exceeds runtime budget"
            }
        }
        return value
    }
}
