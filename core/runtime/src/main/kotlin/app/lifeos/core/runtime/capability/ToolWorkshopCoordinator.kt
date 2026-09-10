package app.lifeos.core.runtime.capability

import app.lifeos.core.field.FieldSnapshotId
import java.time.Instant
import java.util.UUID

interface ToolSpecificationBuilder {
    suspend fun build(gap: CapabilityGap): ToolSpecification
}

interface ToolDesigner {
    suspend fun design(toolId: String, specification: ToolSpecification): ToolDesign
}

interface ToolImplementationEngine {
    suspend fun implement(design: ToolDesign): GeneratedSource
}

interface ToolBuildRunner {
    suspend fun build(source: GeneratedSource): ToolBuildResult
}

interface ToolTestRunner {
    suspend fun test(build: ToolBuildResult): ToolTestResult
}

interface ToolSecurityValidator {
    suspend fun validate(specification: ToolSpecification, source: GeneratedSource): ToolSecurityResult
}

interface GeneratedCapabilityVerifier {
    suspend fun verify(
        specification: ToolSpecification,
        build: ToolBuildResult,
    ): CapabilityVerificationResult
}

fun interface ToolWorkshopFieldEvidenceProvider {
    suspend fun snapshotsFor(gap: CapabilityGap): Set<FieldSnapshotId>
}

sealed interface ToolWorkshopResult {
    data class Verified(
        val record: GeneratedToolRecord,
        val designFieldSnapshotIds: Set<FieldSnapshotId> = emptySet(),
    ) : ToolWorkshopResult

    data class Rejected(val record: GeneratedToolRecord, val reason: String) : ToolWorkshopResult
}

class ToolWorkshopCoordinator(
    private val specificationBuilder: ToolSpecificationBuilder,
    private val designer: ToolDesigner,
    private val implementationEngine: ToolImplementationEngine,
    private val buildRunner: ToolBuildRunner,
    private val testRunner: ToolTestRunner,
    private val securityValidator: ToolSecurityValidator,
    private val capabilityVerifier: GeneratedCapabilityVerifier,
    private val registry: GeneratedToolRegistry,
    private val promotionEvidenceLedger: GeneratedToolPromotionEvidenceLedger? = null,
    private val fieldEvidenceProvider: ToolWorkshopFieldEvidenceProvider =
        ToolWorkshopFieldEvidenceProvider { emptySet() },
    private val now: () -> Instant = Instant::now,
    private val newToolId: () -> String = { "generated-${UUID.randomUUID()}" },
) {
    suspend fun generate(gap: CapabilityGap): ToolWorkshopResult {
        val designFieldSnapshotIds = fieldEvidenceProvider.snapshotsFor(gap).toSet()
        require(designFieldSnapshotIds.isEmpty() || promotionEvidenceLedger != null) {
            "Field design evidence requires a promotion evidence ledger"
        }

        val specification = specificationBuilder.build(gap)
        val toolId = newToolId()
        val design = designer.design(toolId, specification)
        require(design.toolId == toolId) { "Tool designer changed generated tool id" }
        val source = implementationEngine.implement(design)
        require(source.toolId == toolId) { "Tool implementation changed generated tool id" }
        require(source.source.toByteArray(Charsets.UTF_8).size <= specification.maxSourceBytes) {
            "Generated source exceeds specification size limit"
        }

        val security = securityValidator.validate(specification, source)
        if (!security.accepted) {
            val record = registerRejectedWithoutBuild(
                toolId = toolId,
                specification = specification,
                sourceHash = "security-rejected",
                reason = security.violations.joinToString(";").ifBlank { "security-rejected" },
            )
            return ToolWorkshopResult.Rejected(record, record.lastMessage ?: "security-rejected")
        }

        val build = buildRunner.build(source)
        require(build.toolId == toolId) { "Build runner changed generated tool id" }
        val initial = GeneratedToolRecord(
            manifest = manifest(
                toolId = toolId,
                specification = specification,
                sourceHash = build.sourceHash,
                buildHash = build.buildHash,
            ),
            state = GeneratedToolState.GENERATED,
        )
        registry.register(initial)
        if (designFieldSnapshotIds.isNotEmpty()) {
            promotionEvidenceLedger?.recordDesignFieldSnapshots(toolId, designFieldSnapshotIds)
        }

        if (!build.success || build.artifactRef.isNullOrBlank()) {
            val reason = build.diagnostics.joinToString(";").ifBlank { "build-failed" }
            val rejected = registry.transition(toolId, GeneratedToolState.REJECTED, message = reason)
            return ToolWorkshopResult.Rejected(rejected, reason)
        }
        registry.transition(toolId, GeneratedToolState.BUILT, message = "build-succeeded")

        val tests = testRunner.test(build)
        if (!tests.success || tests.failed > 0) {
            val reason = tests.diagnostics.joinToString(";").ifBlank { "tests-failed:${tests.failed}" }
            val rejected = registry.transition(toolId, GeneratedToolState.REJECTED, message = reason)
            return ToolWorkshopResult.Rejected(rejected, reason)
        }
        registry.transition(toolId, GeneratedToolState.TESTED, message = "tests-passed:${tests.passed}")

        val verification = capabilityVerifier.verify(specification, build)
        if (!verification.verified) {
            val reason = verification.diagnostics.joinToString(";").ifBlank { "capability-verification-failed" }
            val rejected = registry.transition(
                toolId,
                GeneratedToolState.REJECTED,
                confidence = verification.confidence,
                message = reason,
            )
            return ToolWorkshopResult.Rejected(rejected, reason)
        }

        val verified = registry.transition(
            toolId,
            GeneratedToolState.VERIFIED,
            confidence = verification.confidence,
            message = "capability-verified",
        )
        return ToolWorkshopResult.Verified(verified, designFieldSnapshotIds)
    }

    private suspend fun registerRejectedWithoutBuild(
        toolId: String,
        specification: ToolSpecification,
        sourceHash: String,
        reason: String,
    ): GeneratedToolRecord {
        registry.register(
            GeneratedToolRecord(
                manifest = manifest(
                    toolId = toolId,
                    specification = specification,
                    sourceHash = sourceHash,
                    buildHash = null,
                ),
                state = GeneratedToolState.GENERATED,
            )
        )
        return registry.transition(toolId, GeneratedToolState.REJECTED, message = reason)
    }

    private fun manifest(
        toolId: String,
        specification: ToolSpecification,
        sourceHash: String,
        buildHash: String?,
    ) = GeneratedToolManifest(
        toolId = toolId,
        sourceCapability = specification.requiredCapability.capabilityId,
        sourceHash = sourceHash,
        buildHash = buildHash,
        permissions = specification.allowedPermissions,
        generatedAt = now(),
        requiredInputs = specification.requiredCapability.requiredInputs,
        requiredOutputs = specification.requiredCapability.requiredOutputs,
    )
}
