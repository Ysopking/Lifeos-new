package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildStudioPolicyTest {
    @Test
    fun `path traversal patch is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            SourcePatchOperation(
                type = SourcePatchOperationType.CREATE,
                path = "core/runtime/../health/ProtectionCoordinator.kt",
                content = "malicious",
            )
        }
    }

    @Test
    fun `protected task ownership root is rejected before workspace`() {
        val spec = spec(
            allowed = setOf(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/tasks",
                TEST_PREFIX,
            )
        )
        val design = BuildDesignSpec(
            buildSpecId = spec.id,
            capability = spec.gap.requirement,
            summary = "unsafe task mutation",
            implementationNotes = emptyList(),
            plannedSourcePaths = setOf(PROTECTED_TASK_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            design.id,
            listOf(
                SourcePatchOperation(SourcePatchOperationType.UPDATE, PROTECTED_TASK_PATH, "changed"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "test"),
            ),
        )

        val failures = BuildPathPolicy().validate(spec, design, patch)

        assertTrue(failures.any { it == "protected-root-path:$PROTECTED_TASK_PATH" })
    }

    @Test
    fun `ownership recovery composition knowledge and resume gates are always protected`() {
        val policy = BuildPathPolicy()
        val roots = listOf(
            "core/model/src/main/kotlin/app/lifeos/core/model/task/Task.kt",
            "core/data/src/main/kotlin/app/lifeos/core/data/task/TaskRepository.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/recovery/LeaseRecoveryService.kt",
            "core/model/src/main/kotlin/app/lifeos/core/model/health/RuntimeProtectionState.kt",
            "core/data/src/main/kotlin/app/lifeos/core/data/world/WorldFormulaVaultCodec.kt",
            "app/src/main/java/app/lifeos/next/kernel/LifeOsKernelFactory.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/LanguageModels.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/LanguageUnderstandingEngine.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/PhotonLanguageContextBuilder.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/ReferenceResolver.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/RuleBasedIntentClassifier.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildVerification.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolTrialLifecycle.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/LanguageGoalCapabilityRouter.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/GoalResumeEngine.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/LocalKnowledgeGoalEngine.kt",
        )

        roots.forEach { path -> assertTrue(policy.isProtected(path), "expected protected root: $path") }
    }

    @Test
    fun `all planned source and tests must be materialized exactly`() {
        val spec = spec()
        val design = BuildDesignSpec(
            buildSpecId = spec.id,
            capability = spec.gap.requirement,
            summary = "safe design",
            implementationNotes = emptyList(),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            design.id,
            listOf(SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "test")),
        )

        assertEquals(
            listOf(
                "missing-materialized-source-patch",
                "missing-planned-patch:$SOURCE_PATH",
            ),
            BuildPathPolicy().validate(spec, design, patch),
        )
    }

    @Test
    fun `required test cannot be satisfied by deleting it`() {
        val spec = spec()
        val design = BuildDesignSpec(
            buildSpecId = spec.id,
            capability = spec.gap.requirement,
            summary = "unsafe test deletion",
            implementationNotes = emptyList(),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            design.id,
            listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "source"),
                SourcePatchOperation(SourcePatchOperationType.DELETE, TEST_PATH),
            ),
        )

        assertEquals(
            listOf("test-delete-forbidden:$TEST_PATH"),
            BuildPathPolicy().validate(spec, design, patch),
        )
    }

    @Test
    fun `verification requires test lint assemble and apk`() {
        val evidence = BuildVerificationEvidence(
            branchName = "buildstudio/candidate-test",
            branchHeadCommit = "b".repeat(40),
            patchPlanId = "patch",
            commandResults = listOf(success(BuildGateCommand.TEST)),
            artifact = null,
        )

        val verification = BuildVerificationPolicy().verify(evidence)

        assertEquals(BuildVerificationStatus.REJECTED, verification.status)
        assertEquals(
            listOf(
                "missing-command:ASSEMBLE_DEBUG",
                "missing-command:LINT_DEBUG",
                "missing-debug-apk-evidence",
            ),
            verification.failures,
        )
    }

    @Test
    fun `verification evidence rejects non candidate branch`() {
        assertFailsWith<IllegalArgumentException> {
            BuildVerificationEvidence(
                branchName = "feature/generated-module",
                branchHeadCommit = "b".repeat(40),
                patchPlanId = "patch",
                commandResults = BuildGateCommand.entries.map { success(it) },
                artifact = BuildArtifactEvidence("artifact://debug.apk", "a".repeat(64)),
            )
        }
    }

    @Test
    fun `patch and design identities are content sensitive but order stable`() {
        val spec = spec()
        val leftDesign = BuildDesignSpec(
            spec.id,
            spec.gap.requirement,
            "design",
            listOf("b", "a"),
            setOf(SOURCE_PATH),
            setOf(TEST_PATH),
        )
        val rightDesign = BuildDesignSpec(
            spec.id,
            spec.gap.requirement,
            "design",
            listOf("a", "b"),
            setOf(SOURCE_PATH),
            setOf(TEST_PATH),
        )
        assertEquals(leftDesign.id, rightDesign.id)

        val left = SourcePatchPlan(
            leftDesign.id,
            listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "source-v1"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "test-v1"),
            ),
        )
        val reordered = SourcePatchPlan(leftDesign.id, left.operations.reversed())
        val changed = SourcePatchPlan(
            leftDesign.id,
            listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "source-v2"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "test-v1"),
            ),
        )

        assertEquals(left.id, reordered.id)
        assertNotEquals(left.id, changed.id)
    }

    private fun success(command: BuildGateCommand) = BuildCommandResult(
        command = command,
        success = true,
        exitCode = 0,
        outputFingerprint = "output-${command.name}",
    )

    private fun spec(allowed: Set<String> = setOf(SOURCE_PREFIX, TEST_PREFIX)): BuildSpec {
        val requirement = CapabilityRequirement(CapabilityId("module.safe"), requiredOutputs = setOf("safe-output"))
        return BuildSpec(
            sourceCommit = "a".repeat(40),
            gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING),
            allowedPathPrefixes = allowed,
            requiredTestPaths = setOf(TEST_PATH),
        )
    }

    companion object {
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/buildstudio/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/SafeModule.kt"
        private const val TEST_PATH = "$TEST_PREFIX/SafeModuleTest.kt"
        private const val PROTECTED_TASK_PATH = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/tasks/DurableTaskEngine.kt"
    }
}
