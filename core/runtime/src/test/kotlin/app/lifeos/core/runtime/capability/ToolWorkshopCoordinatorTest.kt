package app.lifeos.core.runtime.capability

import app.lifeos.core.field.FieldSnapshotId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ToolWorkshopCoordinatorTest {
    private val requirement = CapabilityRequirement(CapabilityId("text.normalize"))
    private val gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING)

    @Test
    fun verifiedToolPassesAllStagesWithoutBecomingActiveAutomatically() = runTest {
        val registry = GeneratedToolRegistry()
        val coordinator = coordinator(
            registry = registry,
            security = ToolSecurityResult(accepted = true),
            tests = ToolTestResult(success = true, passed = 4, failed = 0),
            verification = CapabilityVerificationResult(verified = true, confidence = 0.92),
        )

        val result = assertIs<ToolWorkshopResult.Verified>(coordinator.generate(gap))

        assertEquals("tool-test", result.record.manifest.toolId)
        assertEquals(GeneratedToolState.VERIFIED, result.record.state)
        assertEquals(0.92, result.record.verificationConfidence)
        assertEquals(GeneratedToolState.VERIFIED, registry.get("tool-test")?.state)
        assertEquals(emptySet(), result.designFieldSnapshotIds)
    }

    @Test
    fun fieldSnapshotsAreCapturedBeforeDesignAndBoundToPromotionLedger() = runTest {
        val registry = GeneratedToolRegistry()
        val evidenceLedger = GeneratedToolPromotionEvidenceLedger()
        val snapshotId = FieldSnapshotId("snapshot:tool-design")
        val coordinator = coordinator(
            registry = registry,
            security = ToolSecurityResult(accepted = true),
            tests = ToolTestResult(success = true, passed = 4, failed = 0),
            verification = CapabilityVerificationResult(verified = true, confidence = 0.95),
            promotionEvidenceLedger = evidenceLedger,
            fieldEvidenceProvider = ToolWorkshopFieldEvidenceProvider { requestedGap ->
                assertEquals(gap, requestedGap)
                setOf(snapshotId)
            },
        )

        val result = assertIs<ToolWorkshopResult.Verified>(coordinator.generate(gap))

        assertEquals(setOf(snapshotId), result.designFieldSnapshotIds)
        assertEquals(setOf(snapshotId), evidenceLedger.snapshot("tool-test").fieldSnapshotIds)
    }

    @Test
    fun securityFailureRejectsToolBeforeBuildActivation() = runTest {
        val registry = GeneratedToolRegistry()
        val coordinator = coordinator(
            registry = registry,
            security = ToolSecurityResult(accepted = false, violations = listOf("network-denied")),
            tests = ToolTestResult(success = true, passed = 1, failed = 0),
            verification = CapabilityVerificationResult(verified = true, confidence = 1.0),
        )

        val result = assertIs<ToolWorkshopResult.Rejected>(coordinator.generate(gap))

        assertEquals(GeneratedToolState.REJECTED, result.record.state)
        assertEquals("network-denied", result.reason)
    }

    private fun coordinator(
        registry: GeneratedToolRegistry,
        security: ToolSecurityResult,
        tests: ToolTestResult,
        verification: CapabilityVerificationResult,
        promotionEvidenceLedger: GeneratedToolPromotionEvidenceLedger? = null,
        fieldEvidenceProvider: ToolWorkshopFieldEvidenceProvider =
            ToolWorkshopFieldEvidenceProvider { emptySet() },
    ) = ToolWorkshopCoordinator(
        specificationBuilder = object : ToolSpecificationBuilder {
            override suspend fun build(gap: CapabilityGap) = ToolSpecification(
                purpose = "normalize text",
                requiredCapability = gap.requirement,
            )
        },
        designer = object : ToolDesigner {
            override suspend fun design(toolId: String, specification: ToolSpecification) =
                ToolDesign(toolId, specification, "small deterministic transformer")
        },
        implementationEngine = object : ToolImplementationEngine {
            override suspend fun implement(design: ToolDesign) = GeneratedSource(
                design.toolId,
                "fun normalize(value: String) = value.trim()",
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
            override suspend fun test(build: ToolBuildResult) = tests
        },
        securityValidator = object : ToolSecurityValidator {
            override suspend fun validate(
                specification: ToolSpecification,
                source: GeneratedSource,
            ) = security
        },
        capabilityVerifier = object : GeneratedCapabilityVerifier {
            override suspend fun verify(
                specification: ToolSpecification,
                build: ToolBuildResult,
            ) = verification
        },
        registry = registry,
        promotionEvidenceLedger = promotionEvidenceLedger,
        fieldEvidenceProvider = fieldEvidenceProvider,
        now = { Instant.parse("2026-09-07T18:00:00Z") },
        newToolId = { "tool-test" },
    )
}
