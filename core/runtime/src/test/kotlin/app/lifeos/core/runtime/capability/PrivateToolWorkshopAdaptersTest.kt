package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PrivateToolWorkshopAdaptersTest {
    private val t0 = Instant.parse("2026-09-10T20:00:00Z")

    @Test
    fun `program codec and interpreter are deterministic`() {
        val program = GeneratedToolProgram(
            toolId = "tool-1",
            capabilityId = CapabilityId("text.normalize"),
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
            instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.NORMALIZE_WHITESPACE)),
        )

        val encoded = GeneratedToolProgramCodec.encode(program)
        val decoded = GeneratedToolProgramCodec.decode(encoded)
        val output = GeneratedToolProgramInterpreter().execute(decoded, "  LIFEOS   private\n v1  ")

        assertEquals(program, decoded)
        assertEquals("LIFEOS private v1", output)
        assertTrue(decoded.executable)
    }

    @Test
    fun `supported bounded capability reaches trial through real adapters`() = runTest {
        val registry = GeneratedToolRegistry()
        val catalog = PrivateToolBuildCatalog()
        val genesis = GeneratedToolGenesisCoordinator(
            workshop = workshop(registry, catalog),
            lifecycle = GeneratedToolLifecycleCoordinator(registry),
        )

        val result = assertIs<GeneratedToolGenesisResult.TrialReady>(
            genesis.generateFor(
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

        assertEquals(GeneratedToolState.TRIAL, result.record.state)
        assertEquals(0.95, result.record.verificationConfidence)
        assertTrue(result.record.manifest.buildHash?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue(result.record.manifest.permissions.isEmpty())
    }

    @Test
    fun `unsupported buildstudio archetype is rejected before build`() = runTest {
        val registry = GeneratedToolRegistry()
        val catalog = PrivateToolBuildCatalog()
        val workshop = workshop(registry, catalog)

        val result = assertIs<ToolWorkshopResult.Rejected>(
            workshop.generate(
                CapabilityGap(
                    requirement = CapabilityRequirement(
                        capabilityId = CapabilityId("buildstudio.run"),
                        requiredInputs = setOf("goal-photon"),
                        requiredOutputs = setOf("build-artifact"),
                    ),
                    type = CapabilityGapType.CAPABILITY_MISSING,
                )
            )
        )

        assertEquals(GeneratedToolState.REJECTED, result.record.state)
        assertTrue(result.reason.contains("unsupported-tool-archetype"))
        assertFalse(result.record.manifest.buildHash?.isNotBlank() == true)
    }

    private fun workshop(
        registry: GeneratedToolRegistry,
        catalog: PrivateToolBuildCatalog,
    ) = ToolWorkshopCoordinator(
        specificationBuilder = PrivateToolSpecificationBuilder(),
        designer = PrivateToolDesigner(),
        implementationEngine = PrivateToolImplementationEngine(),
        buildRunner = PrivateToolBuildRunner(catalog),
        testRunner = PrivateToolTestRunner(catalog),
        securityValidator = PrivateToolSecurityValidator(),
        capabilityVerifier = PrivateGeneratedCapabilityVerifier(catalog),
        registry = registry,
        now = { t0 },
        newToolId = { "tool-private-v1" },
    )
}
