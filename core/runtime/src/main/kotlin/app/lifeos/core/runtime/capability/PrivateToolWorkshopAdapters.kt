package app.lifeos.core.runtime.capability

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class PrivateToolSpecificationBuilder : ToolSpecificationBuilder {
    override suspend fun build(gap: CapabilityGap): ToolSpecification = ToolSpecification(
        purpose = "bounded-local:${gap.requirement.capabilityId.value}",
        requiredCapability = gap.requirement,
        allowedPermissions = emptySet(),
        forbiddenSideEffects = setOf(
            "network-access",
            "filesystem-write",
            "database-write",
            "worker-start",
            "tool-invocation",
            "memory-write",
            "repository-read",
            "repository-modification",
            "reflection",
            "process-execution",
        ),
        maxSourceBytes = MAX_SOURCE_BYTES,
    )

    private companion object {
        const val MAX_SOURCE_BYTES = 32_768L
    }
}

class PrivateToolDesigner : ToolDesigner {
    override suspend fun design(toolId: String, specification: ToolSpecification): ToolDesign =
        ToolDesign(
            toolId = toolId,
            specification = specification,
            implementationNotes = when (opcodeFor(specification.requiredCapability.capabilityId.value)) {
                GeneratedToolOpcode.TRIM -> "bounded-op:TRIM"
                GeneratedToolOpcode.NORMALIZE_WHITESPACE -> "bounded-op:NORMALIZE_WHITESPACE"
                GeneratedToolOpcode.UPPERCASE -> "bounded-op:UPPERCASE"
                GeneratedToolOpcode.LOWERCASE -> "bounded-op:LOWERCASE"
                GeneratedToolOpcode.UNSUPPORTED -> "bounded-op:UNSUPPORTED"
            },
        )
}

class PrivateToolImplementationEngine : ToolImplementationEngine {
    override suspend fun implement(design: ToolDesign): GeneratedSource {
        val opcode = design.implementationNotes
            .removePrefix("bounded-op:")
            .let(GeneratedToolOpcode::valueOf)
        val requirement = design.specification.requiredCapability
        val program = GeneratedToolProgram(
            toolId = design.toolId,
            capabilityId = requirement.capabilityId,
            requiredInputs = requirement.requiredInputs,
            requiredOutputs = requirement.requiredOutputs,
            instructions = listOf(GeneratedToolInstruction(opcode)),
        )
        return GeneratedSource(
            toolId = design.toolId,
            source = GeneratedToolProgramCodec.encode(program),
        )
    }
}

class PrivateToolSecurityValidator : ToolSecurityValidator {
    override suspend fun validate(
        specification: ToolSpecification,
        source: GeneratedSource,
    ): ToolSecurityResult {
        val violations = mutableListOf<String>()
        val program = runCatching { GeneratedToolProgramCodec.decode(source.source) }
            .getOrElse {
                return ToolSecurityResult(false, listOf("program-decode-failed"))
            }
        if (program.toolId != source.toolId) violations += "tool-id-mismatch"
        if (program.capabilityId != specification.requiredCapability.capabilityId) {
            violations += "capability-mismatch"
        }
        if (program.requiredInputs != specification.requiredCapability.requiredInputs) {
            violations += "input-contract-mismatch"
        }
        if (program.requiredOutputs != specification.requiredCapability.requiredOutputs) {
            violations += "output-contract-mismatch"
        }
        if (specification.allowedPermissions.isNotEmpty()) {
            violations += "bounded-runtime-permissions-must-be-empty"
        }
        if (program.instructions.size != 1) violations += "instruction-count-unsupported"
        if (!program.executable) violations += "unsupported-tool-archetype"
        if (source.source.toByteArray(StandardCharsets.UTF_8).size > specification.maxSourceBytes) {
            violations += "source-budget-exceeded"
        }
        return ToolSecurityResult(
            accepted = violations.isEmpty(),
            violations = violations.distinct().sorted(),
        )
    }
}

internal data class PrivateBuiltToolProgram(
    val program: GeneratedToolProgram,
    val source: String,
    val sourceHash: String,
    val buildHash: String,
)

class PrivateToolBuildCatalog {
    private val built = ConcurrentHashMap<String, PrivateBuiltToolProgram>()
    private val tested = ConcurrentHashMap.newKeySet<String>()

    internal fun put(value: PrivateBuiltToolProgram) {
        built[value.buildHash] = value
    }

    internal fun get(buildHash: String): PrivateBuiltToolProgram? = built[buildHash]

    internal fun markTested(buildHash: String) {
        require(built.containsKey(buildHash)) { "Cannot mark unknown bounded tool build as tested" }
        tested += buildHash
    }

    internal fun wasTested(buildHash: String): Boolean = buildHash in tested
}

class PrivateToolBuildRunner(
    private val catalog: PrivateToolBuildCatalog,
) : ToolBuildRunner {
    override suspend fun build(source: GeneratedSource): ToolBuildResult {
        val program = runCatching { GeneratedToolProgramCodec.decode(source.source) }
            .getOrElse { error("Bounded tool source failed strict decode") }
        require(program.toolId == source.toolId) { "Bounded tool build changed tool identity" }
        val sourceHash = GeneratedToolArtifact.typedSourceHash(source.source)
        val buildHash = sha256(
            (GeneratedToolArtifact.BUILD_DOMAIN + source.source).toByteArray(StandardCharsets.UTF_8)
        )
        catalog.put(
            PrivateBuiltToolProgram(
                program = program,
                source = source.source,
                sourceHash = sourceHash,
                buildHash = buildHash,
            )
        )
        return ToolBuildResult(
            toolId = source.toolId,
            artifactRef = "lifeos-bounded-tool://${source.toolId}/$buildHash",
            sourceHash = sourceHash,
            buildHash = buildHash,
            success = true,
        )
    }
}

class PrivateToolTestRunner(
    private val catalog: PrivateToolBuildCatalog,
    private val interpreter: GeneratedToolProgramInterpreter = GeneratedToolProgramInterpreter(),
) : ToolTestRunner {
    override suspend fun test(build: ToolBuildResult): ToolTestResult {
        val buildHash = build.buildHash
            ?: return ToolTestResult(false, passed = 0, failed = 1, diagnostics = listOf("missing-build-hash"))
        val built = catalog.get(buildHash)
            ?: return ToolTestResult(false, passed = 0, failed = 1, diagnostics = listOf("build-artifact-missing"))
        if (built.program.toolId != build.toolId) {
            return ToolTestResult(false, passed = 0, failed = 1, diagnostics = listOf("build-tool-id-mismatch"))
        }

        val cases = testCasesFor(built.program.instructions.single().opcode)
        if (cases.isEmpty()) {
            return ToolTestResult(false, passed = 0, failed = 1, diagnostics = listOf("unsupported-tool-archetype"))
        }
        val failures = cases.filterNot { case ->
            runCatching { interpreter.execute(built.program, case.input) }.getOrNull() == case.expected
        }
        if (failures.isNotEmpty()) {
            return ToolTestResult(
                success = false,
                passed = cases.size - failures.size,
                failed = failures.size,
                diagnostics = failures.map { "case-failed:${it.id}" },
            )
        }
        catalog.markTested(buildHash)
        return ToolTestResult(success = true, passed = cases.size, failed = 0)
    }
}

class PrivateGeneratedCapabilityVerifier(
    private val catalog: PrivateToolBuildCatalog,
) : GeneratedCapabilityVerifier {
    override suspend fun verify(
        specification: ToolSpecification,
        build: ToolBuildResult,
    ): CapabilityVerificationResult {
        val buildHash = build.buildHash
        val built = buildHash?.let(catalog::get)
        val diagnostics = buildList {
            if (!build.success) add("build-not-successful")
            if (buildHash == null || !buildHash.matches(Regex("[0-9a-f]{64}"))) add("invalid-build-hash")
            if (built == null) add("build-artifact-missing")
            if (buildHash != null && !catalog.wasTested(buildHash)) add("build-not-tested")
            if (built != null) {
                if (!built.program.executable) add("program-not-executable")
                if (built.program.capabilityId != specification.requiredCapability.capabilityId) {
                    add("capability-mismatch")
                }
                if (built.program.requiredInputs != specification.requiredCapability.requiredInputs) {
                    add("input-contract-mismatch")
                }
                if (built.program.requiredOutputs != specification.requiredCapability.requiredOutputs) {
                    add("output-contract-mismatch")
                }
                if (built.sourceHash != build.sourceHash) add("source-hash-mismatch")
                if (built.buildHash != buildHash) add("build-hash-mismatch")
            }
        }
        return CapabilityVerificationResult(
            verified = diagnostics.isEmpty(),
            confidence = if (diagnostics.isEmpty()) 0.95 else 0.0,
            diagnostics = diagnostics,
        )
    }
}

private data class PrivateToolTestCase(
    val id: String,
    val input: String,
    val expected: String,
)

private fun testCasesFor(opcode: GeneratedToolOpcode): List<PrivateToolTestCase> = when (opcode) {
    GeneratedToolOpcode.TRIM -> listOf(
        PrivateToolTestCase("trim-spaces", "  hello  ", "hello"),
        PrivateToolTestCase("trim-lines", "\nhello\n", "hello"),
    )
    GeneratedToolOpcode.NORMALIZE_WHITESPACE -> listOf(
        PrivateToolTestCase("normalize-spaces", "  hello   world  ", "hello world"),
        PrivateToolTestCase("normalize-lines", "hello\n\tworld", "hello world"),
    )
    GeneratedToolOpcode.UPPERCASE -> listOf(
        PrivateToolTestCase("uppercase", "LifeOS 123", "LIFEOS 123"),
        PrivateToolTestCase("uppercase-stable", "abc", "ABC"),
    )
    GeneratedToolOpcode.LOWERCASE -> listOf(
        PrivateToolTestCase("lowercase", "LifeOS 123", "lifeos 123"),
        PrivateToolTestCase("lowercase-stable", "ABC", "abc"),
    )
    GeneratedToolOpcode.UNSUPPORTED -> emptyList()
}

private fun opcodeFor(capabilityId: String): GeneratedToolOpcode {
    val normalized = capabilityId.lowercase()
    return when {
        "normalize" in normalized -> GeneratedToolOpcode.NORMALIZE_WHITESPACE
        "uppercase" in normalized || "upper-case" in normalized -> GeneratedToolOpcode.UPPERCASE
        "lowercase" in normalized || "lower-case" in normalized -> GeneratedToolOpcode.LOWERCASE
        "trim" in normalized -> GeneratedToolOpcode.TRIM
        else -> GeneratedToolOpcode.UNSUPPORTED
    }
}
