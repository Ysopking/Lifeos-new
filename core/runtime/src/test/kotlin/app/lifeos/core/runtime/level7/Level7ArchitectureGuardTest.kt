package app.lifeos.core.runtime.level7

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private object Level7SourceArchitecture {
    val root: Path by lazy {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(8) {
            if (current.resolve("settings.gradle.kts").exists() ||
                current.resolve("settings.gradle").exists()
            ) return@lazy current
            current = current.parent ?: return@repeat
        }
        error("Unable to locate LIFEOS repository root from ${System.getProperty("user.dir")}")
    }

    fun text(relative: String): String {
        val path = root.resolve(relative)
        require(path.exists()) { "Missing architecture source: $relative" }
        return path.readText()
    }

    fun filesUnder(relative: String): List<Path> {
        val base = root.resolve(relative)
        if (!base.exists() || !base.isDirectory()) return emptyList()
        return Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".kt") || it.fileName.toString().startsWith("build.gradle") }
                .toList()
        }
    }
}

class NoFoundationModelDependencyTest {
    @Test
    fun productionBuildsHaveNoFoundationModelRuntimeDependency() {
        val buildFiles = Files.walk(Level7SourceArchitecture.root, 5).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().startsWith("build.gradle") }
                .toList()
        }
        val forbidden = listOf(
            "com.openai",
            "anthropic",
            "onnxruntime-genai",
            "transformers",
            "llama.cpp",
            "tensorflow-lite-task-text",
        )
        val violations = buildFiles.flatMap { file ->
            val text = file.readText().lowercase()
            forbidden.filter { token -> token in text }
                .map { token -> "${Level7SourceArchitecture.root.relativize(file)}:$token" }
        }
        assertTrue(violations.isEmpty(), "Foundation-model runtime dependencies found: $violations")
    }
}

class SingleBootEngineOwnerTest {
    @Test
    fun continuousLearningHasNoIndependentPollingLoop() {
        val learning = Level7SourceArchitecture.text(
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/learning/ContinuousLearningCoordinator.kt"
        )
        val continuous = Level7SourceArchitecture.text(
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/boot/ContinuousBootEngine.kt"
        )
        assertFalse("while (isActive)" in learning)
        assertTrue("processAvailable" in learning)
        assertTrue("class ContinuousBootEngine" in continuous)
    }
}

class SingleWorldFormulaCouplingPathTest {
    @Test
    fun productiveAppAndGoalPathsDoNotInstantiateLowLevelConvergenceDirectly() {
        val app = Level7SourceArchitecture.text(
            "app/src/main/java/app/lifeos/next/LifeOsApplication.kt"
        )
        val goal = Level7SourceArchitecture.text(
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/GoalConvergenceDecisionProvider.kt"
        )
        val thought = Level7SourceArchitecture.text(
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/convergence/ThoughtGraphConvergenceService.kt"
        )
        assertFalse("ConvergenceCoordinator(" in app)
        assertFalse("DurableConvergenceDecisionCoordinator(" in app)
        assertFalse("ConvergenceCoordinator(" in goal)
        assertFalse("DurableConvergenceDecisionCoordinator(" in goal)
        assertTrue("ProductiveConvergenceAuthority" in goal)
        assertTrue("WorldFormulaBoundConvergenceService" in thought)
    }
}

class NoProductiveFullVaultScanTest {
    @Test
    fun productiveCognitionGoalDeepSearchAndPlanningHaveNoLoadAll() {
        val productiveFiles = listOf(
            "app/src/main/java/app/lifeos/next/kernel/LifeOsKernel.kt",
            "app/src/main/java/app/lifeos/next/LifeOsApplication.kt",
            "app/src/main/java/app/lifeos/next/kernel/DurableGoalPlanRuntime.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/FuturePlanningRuntime.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/GoalConvergenceDecisionProvider.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/query/ProductivePhotonQueryService.kt",
        )
        val violations = productiveFiles.filter { relative ->
            ".loadAll()" in Level7SourceArchitecture.text(relative)
        }
        assertTrue(violations.isEmpty(), "Productive full-vault scans remain: $violations")
    }
}

class NoDirectWorldMutationTest {
    @Test
    fun level7LearningAndModulesDoNotOwnProductiveWorldHeadMutation() {
        val files = Level7SourceArchitecture.filesUnder(
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/level7"
        )
        val violations = files.filter { file ->
            val text = file.readText()
            "ProductiveWorldHeadRepository" in text ||
                "ProductiveWorldHeadCommitter" in text ||
                "compareAndSet(" in text && "WorldModelRepository" !in file.fileName.toString()
        }
        assertTrue(
            violations.isEmpty(),
            "Level7 subsystem directly owns productive world mutation: $violations",
        )
    }
}

class NoUnversionedPhysicsTest {
    @Test
    fun productiveWorldAndBootCycleRequireEquationVersion() {
        val productive = Level7SourceArchitecture.text(
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/world/ProductiveWorldFormula.kt"
        )
        val boot = Level7SourceArchitecture.text(
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/boot/BootEngineRuntime.kt"
        )
        assertTrue("val equationVersion: String" in productive)
        assertTrue("require(equationVersion.isNotBlank())" in productive)
        assertTrue("frozenInputs.equationVersion" in boot)
    }
}

class ProtectedRootAuthorityTest {
    @Test
    fun callerCannotChooseNullComponentToBypassRootClassification() {
        val registry = ProtectedRootRegistry()
        val component = registry.classify(
            MutationTarget(
                path = "core/runtime/boot/BootEngineRuntime.kt",
                type = "BootEngineRuntime",
            )
        )
        assertNotNull(component)
        assertTrue(component == ProtectedRootComponent.BOOTENGINE_LIFECYCLE)
        val request = RootMutationRequest(
            subjectId = "architecture-test",
            target = MutationTarget(
                path = "core/runtime/world/ProductiveWorldFormula.kt",
                type = "ProductiveWorldHead",
            ),
            candidateFingerprint = "candidate",
        )
        assertTrue(ProtectedRootFirewall.evaluate(request) is RootMutationDecision.BlockedProtectedRoot)
    }
}

class NoSyntheticGoldEvidenceTest {
    @Test
    fun goldVerifierAcceptsTypedProofsNotArbitraryStringMap() {
        val source = Level7SourceArchitecture.text(
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/level7/Level7Gold.kt"
        )
        assertTrue("sealed interface Level7InvariantProof" in source)
        assertFalse("invariantEvidence: Map<String, String>" in source)
        assertFalse("Map<String, String>" in source)
        assertTrue("NovelDomainProof" in source)
        assertTrue("RecoveryProof" in source)
        assertTrue("RollbackProof" in source)
    }
}
