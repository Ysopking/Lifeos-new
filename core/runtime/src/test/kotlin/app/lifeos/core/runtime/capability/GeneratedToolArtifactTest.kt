package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GeneratedToolArtifactTest {
    private val t0 = Instant.parse("2026-09-10T20:00:00Z")

    @Test
    fun `artifact codec round trips exact canonical program`() {
        val program = program("tool-artifact")
        val artifact = GeneratedToolArtifact.create(
            toolId = program.toolId,
            canonicalProgram = GeneratedToolProgramCodec.encode(program),
            createdAt = t0,
        )

        val decoded = GeneratedToolArtifactCodec.decode(
            GeneratedToolArtifactCodec.encode(listOf(artifact))
        )

        assertEquals(listOf(artifact), decoded)
        assertTrue(artifact.sourceHash.matches(Regex("bounded-v1:[0-9a-f]{64}")))
        assertTrue(GeneratedToolArtifact.isBoundedSourceHash(artifact.sourceHash))
        assertTrue(artifact.buildHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `artifact rejects changed canonical source under stale hashes`() {
        val original = GeneratedToolArtifact.create(
            toolId = "tool-artifact",
            canonicalProgram = GeneratedToolProgramCodec.encode(program("tool-artifact")),
            createdAt = t0,
        )
        val changed = GeneratedToolProgramCodec.encode(
            program("tool-artifact").copy(
                instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.UPPERCASE))
            )
        )

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolArtifact(
                toolId = original.toolId,
                canonicalProgram = changed,
                sourceHash = original.sourceHash,
                buildHash = original.buildHash,
                createdAt = original.createdAt,
            )
        }
    }

    @Test
    fun `workshop persists exact artifact before verified`() = runTest {
        val registry = GeneratedToolRegistry()
        val catalog = PrivateToolBuildCatalog()
        val artifacts = InMemoryArtifactRepository()
        val workshop = ToolWorkshopCoordinator(
            specificationBuilder = PrivateToolSpecificationBuilder(),
            designer = PrivateToolDesigner(),
            implementationEngine = PrivateToolImplementationEngine(),
            buildRunner = PrivateToolBuildRunner(catalog),
            testRunner = PrivateToolTestRunner(catalog),
            securityValidator = PrivateToolSecurityValidator(),
            capabilityVerifier = PrivateGeneratedCapabilityVerifier(catalog),
            registry = registry,
            now = { t0 },
            newToolId = { "tool-artifact" },
            artifactRepository = artifacts,
        )

        val result = assertIs<ToolWorkshopResult.Verified>(
            workshop.generate(
                CapabilityGap(
                    requirement = CapabilityRequirement(
                        capabilityId = CapabilityId("text.normalize"),
                        requiredInputs = setOf("text"),
                        requiredOutputs = setOf("normalized-text"),
                    ),
                    type = CapabilityGapType.CAPABILITY_MISSING,
                )
            )
        )

        val artifact = artifacts.load("tool-artifact")
        assertTrue(artifact != null)
        assertTrue(artifact.matches(result.record))
        assertEquals(result.record.manifest.sourceHash, artifact.sourceHash)
        assertEquals(result.record.manifest.buildHash, artifact.buildHash)
    }

    private fun program(toolId: String) = GeneratedToolProgram(
        toolId = toolId,
        capabilityId = CapabilityId("text.normalize"),
        requiredInputs = setOf("text"),
        requiredOutputs = setOf("normalized-text"),
        instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.NORMALIZE_WHITESPACE)),
    )

    private class InMemoryArtifactRepository : GeneratedToolArtifactRepository {
        private val values = linkedMapOf<String, GeneratedToolArtifact>()

        override suspend fun persist(artifact: GeneratedToolArtifact) {
            values[artifact.toolId]?.let { require(it == artifact) }
            values[artifact.toolId] = artifact
        }

        override suspend fun load(toolId: String): GeneratedToolArtifact? = values[toolId]

        override suspend fun loadAll(): List<GeneratedToolArtifact> = values.values.sortedBy { it.toolId }
    }
}
