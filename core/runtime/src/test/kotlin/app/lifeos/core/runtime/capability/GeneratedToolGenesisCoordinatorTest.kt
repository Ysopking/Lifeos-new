package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GeneratedToolGenesisCoordinatorTest {
    private val t0 = Instant.parse("2026-09-07T19:00:00Z")
    private val gap = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = CapabilityId("text.normalize"),
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        type = CapabilityGapType.CAPABILITY_MISSING,
    )

    @Test
    fun successfulGenesisStopsAtTrial() = runTest {
        val registry = GeneratedToolRegistry()
        val genesis = GeneratedToolGenesisCoordinator(
            workshop = workshop(registry, permissions = emptySet()),
            lifecycle = GeneratedToolLifecycleCoordinator(registry),
        )

        val result = assertIs<GeneratedToolGenesisResult.TrialReady>(
            genesis.generateFor(gap)
        )

        assertEquals(GeneratedToolState.TRIAL, result.record.state)
        assertEquals(setOf("text"), result.record.manifest.requiredInputs)
        assertEquals(setOf("normalized-text"), result.record.manifest.requiredOutputs)
        assertEquals(GeneratedToolState.TRIAL, registry.get("tool-genesis")?.state)
    }

    @Test
    fun sandboxDeniedGenesisNeverReachesTrial() = runTest {
        val registry = GeneratedToolRegistry()
        val genesis = GeneratedToolGenesisCoordinator(
            workshop = workshop(
                registry,
                permissions = setOf(ToolPermission.MODIFY_REPOSITORY),
            ),
            lifecycle = GeneratedToolLifecycleCoordinator(registry),
        )

        val result = assertIs<GeneratedToolGenesisResult.Rejected>(
            genesis.generateFor(gap)
        )

        assertEquals(GeneratedToolState.REJECTED, result.record.state)
        assertEquals(GeneratedToolState.REJECTED, registry.get("tool-genesis")?.state)
    }

    private fun workshop(
        registry: GeneratedToolRegistry,
        permissions: Set<ToolPermission>,
    ) = ToolWorkshopCoordinator(
        specificationBuilder = object : ToolSpecificationBuilder {
            override suspend fun build(gap: CapabilityGap) = ToolSpecification(
                purpose = "normalize text",
                requiredCapability = gap.requirement,
                allowedPermissions = permissions,
            )
        },
        designer = object : ToolDesigner {
            override suspend fun design(toolId: String, specification: ToolSpecification) =
                ToolDesign(toolId, specification, "deterministic local transformer")
        },
        implementationEngine = object : ToolImplementationEngine {
            override suspend fun implement(design: ToolDesign) = GeneratedSource(
                toolId = design.toolId,
                source = "fun normalize(value: String) = value.trim()",
            )
        },
        buildRunner = object : ToolBuildRunner {
            override suspend fun build(source: GeneratedSource) = ToolBuildResult(
                toolId = source.toolId,
                artifactRef = "artifact.jar",
                sourceHash = "source-hash",
                buildHash = "build-hash",
                success = true,
            )
        },
        testRunner = object : ToolTestRunner {
            override suspend fun test(build: ToolBuildResult) = ToolTestResult(
                success = true,
                passed = 4,
                failed = 0,
            )
        },
        securityValidator = object : ToolSecurityValidator {
            override suspend fun validate(
                specification: ToolSpecification,
                source: GeneratedSource,
            ) = ToolSecurityResult(accepted = true)
        },
        capabilityVerifier = object : GeneratedCapabilityVerifier {
            override suspend fun verify(
                specification: ToolSpecification,
                build: ToolBuildResult,
            ) = CapabilityVerificationResult(
                verified = true,
                confidence = 0.95,
            )
        },
        registry = registry,
        now = { t0 },
        newToolId = { "tool-genesis" },
    )
}
