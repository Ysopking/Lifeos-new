package app.lifeos.next

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.task.EncryptedTaskRepository
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.GeneratedToolGenesisResult
import app.lifeos.core.runtime.capability.GeneratedToolRequestExecutionResult
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.evolution.PrivateNovelCapabilityActivationResult
import app.lifeos.next.kernel.KernelBootstrapState
import app.lifeos.next.kernel.KernelBootstrapStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android/Keystore recovery contract for the private generated-tool path.
 *
 * The emulator workflow invokes seed first, force-stops and cold-starts the target app, then invokes
 * recovery. Seed performs explicit Genesis -> TRIAL and the separate owner activation action. The
 * second process must recover the exact bounded ACTIVE receipt/artifact/seal identities and the eight
 * immutable trial results without manufacturing fresh activation authority.
 */
@RunWith(AndroidJUnit4::class)
class PrivateV1DeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun seedGeneratedToolAndAssertRuntime() {
        runBlocking {
            val boot = awaitBoot()
            assertTrue("Kernel must complete boot before emulator recovery seed", boot.ready)

            val result = app.kernel.generateExplicitlyApprovedTool(
                CapabilityGap(
                    requirement = CapabilityRequirement(
                        capabilityId = CapabilityId("text.uppercase.local"),
                        severity = GapSeverity.BLOCKING,
                        requiredInputs = setOf("text"),
                        requiredOutputs = setOf("text"),
                    ),
                    type = CapabilityGapType.CAPABILITY_MISSING,
                )
            )
            assertTrue(result.execution is GeneratedToolRequestExecutionResult.Completed)
            val completed = result.execution as GeneratedToolRequestExecutionResult.Completed
            assertTrue(completed.genesis is GeneratedToolGenesisResult.TrialReady)
            val genesis = completed.genesis as GeneratedToolGenesisResult.TrialReady
            val trials = result.trials
            assertNotNull("Explicit generated-tool action must execute private trial suite", trials)
            assertTrue("Private trial suite must satisfy all expected cases", trials!!.completeAndExpected)
            assertEquals(3, trials.finalStats?.trials)

            val trialStatus = app.generatedToolStatusReader.snapshot()
            val trialTool = trialStatus.tools.single { it.toolId == genesis.record.manifest.toolId }
            assertEquals(GeneratedToolState.TRIAL, trialTool.state)
            assertEquals(3, trialTool.trials)
            assertEquals(3, trialTool.successes)
            assertEquals(3, trialTool.expectedOutputs)
            assertEquals(0, trialTool.safetyViolations)
            assertEquals(0, trialStatus.activeTools)
            assertTrue(trialTool.promotionEvidenceId == null)

            val activation = app.kernel.reviewAndActivateGeneratedTool(trialTool.toolId)
            assertTrue(
                "Second explicit owner action must pass bounded Novel Canary promotion",
                activation is PrivateNovelCapabilityActivationResult.Activated,
            )
            val activated = activation as PrivateNovelCapabilityActivationResult.Activated
            assertEquals(5, activated.canaryExecutions.size)
            assertEquals(GeneratedToolState.ACTIVE, activated.promotion.activeRecord.state)

            val activeStatus = app.generatedToolStatusReader.snapshot()
            val activeTool = activeStatus.tools.single { it.toolId == trialTool.toolId }
            assertEquals(GeneratedToolState.ACTIVE, activeTool.state)
            assertEquals(8, activeTool.trials)
            assertEquals(8, activeTool.successes)
            assertEquals(8, activeTool.expectedOutputs)
            assertEquals(0, activeTool.safetyViolations)
            assertEquals(1, activeStatus.activeTools)
            assertNotNull(activeTool.promotionEvidenceId)
            assertNotNull(activeTool.boundedAdmissionEvidenceId)
            assertNotNull(activeTool.boundedReadinessEvidenceId)
            assertNotNull(activeTool.boundedPromotionSealId)
            assertEquals(activated.promotion.evidence.id, activeTool.promotionEvidenceId)
            assertEquals(activated.promotion.evidence.novelAdmissionEvidenceId, activeTool.boundedAdmissionEvidenceId)
            assertEquals(activated.promotion.readiness.id, activeTool.boundedReadinessEvidenceId)
            assertEquals(activated.promotion.seal.id, activeTool.boundedPromotionSealId)

            val sentinel = Photon(
                content = listOf(
                    activeTool.toolId,
                    requireNotNull(activeTool.promotionEvidenceId),
                    requireNotNull(activeTool.boundedReadinessEvidenceId),
                    requireNotNull(activeTool.boundedPromotionSealId),
                ).joinToString(SENTINEL_SEPARATOR),
                provenance = Provenance("private-v1-emulator-recovery", "instrumentation"),
                tags = setOf(SENTINEL_TAG),
            )
            val persisted = app.kernel.persistAndIngest(sentinel)
            assertEquals(sentinel, app.kernel.photonStore.load(sentinel.id))
            assertTrue("Recovery sentinel must enter durable cognition", persisted.processingQueued)

            withTimeout(BOOT_TIMEOUT_MS) {
                app.kernel.matrix.state.first { it.nodes[sentinel.id]?.revision == sentinel.revision }
            }
            assertTrue(app.kernel.persistAndIngest(sentinel).processingQueued)
            assertSingleCognitiveTask(sentinel)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
                scenario.recreate()
                scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
            }

            // Simulate the exact durable-save / missing-task crash window. The workflow kills
            // this target process next; only cold-start reconciliation may create its task.
            val orphan = Photon(
                content = "saved-before-cognition-task",
                provenance = Provenance("v2-cognition-recovery", "instrumentation"),
                tags = setOf(ORPHAN_TAG),
            )
            app.kernel.photonStore.save(orphan)
            assertEquals(orphan, app.kernel.photonStore.load(orphan.id))
            assertTrue(cognitiveTasks(orphan).isEmpty())
        }
    }

    @Test
    fun assertRecoveredRuntimeAndToolEvidence() {
        runBlocking {
            val boot = awaitBoot()
            assertTrue("Kernel must recover after target process cold restart", boot.ready)

            val sentinel = app.kernel.photonStore.loadAll()
                .singleOrNull { SENTINEL_TAG in it.tags }
            assertNotNull("Cold restart must preserve encrypted recovery sentinel", sentinel)
            assertSingleCognitiveTask(sentinel!!)
            val orphan = app.kernel.photonStore.loadAll().single { ORPHAN_TAG in it.tags }
            assertSingleCognitiveTask(orphan)
            assertTrue(app.kernel.persistAndIngest(orphan).processingQueued)
            assertSingleCognitiveTask(orphan)
            withTimeout(BOOT_TIMEOUT_MS) {
                app.kernel.matrix.state.first { it.nodes[orphan.id]?.revision == orphan.revision }
            }
            val expected = sentinel.content.split(SENTINEL_SEPARATOR)
            assertEquals("Recovery sentinel must bind tool plus exact evidence ids", 4, expected.size)
            val toolId = expected[0]
            val promotionEvidenceId = expected[1]
            val readinessEvidenceId = expected[2]
            val promotionSealId = expected[3]
            assertTrue("Recovery sentinel must contain generated tool id", toolId.isNotBlank())

            val status = app.generatedToolStatusReader.snapshot()
            val tool = status.tools.single { it.toolId == toolId }
            assertEquals(GeneratedToolState.ACTIVE, tool.state)
            assertEquals(8, tool.trials)
            assertEquals(8, tool.successes)
            assertEquals(8, tool.expectedOutputs)
            assertEquals(0, tool.safetyViolations)
            assertEquals(1, status.activeTools)
            assertEquals(promotionEvidenceId, tool.promotionEvidenceId)
            assertEquals(readinessEvidenceId, tool.boundedReadinessEvidenceId)
            assertEquals(promotionSealId, tool.boundedPromotionSealId)
            assertNotNull(tool.boundedAdmissionEvidenceId)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
            }
        }
    }

    private suspend fun cognitiveTasks(photon: Photon) =
        EncryptedTaskRepository(instrumentation.targetContext).loadReport().also {
            assertTrue("Task vault must remain readable", it.unreadableEntries.isEmpty())
        }.tasks.filter {
            (it.type == TaskType.PROCESS_PHOTON || it.type == TaskType.REPROCESS_PHOTON) &&
                it.inputPhotonRevisions[photon.id] == photon.revision
        }

    private suspend fun assertSingleCognitiveTask(photon: Photon) {
        assertEquals("Each photon revision must have exactly one durable cognition task",
            1, cognitiveTasks(photon).size)
    }

    private suspend fun awaitBoot(): KernelBootstrapState = withTimeout(BOOT_TIMEOUT_MS) {
        app.kernel.bootstrapState.first { state ->
            state.status == KernelBootstrapStatus.READY ||
                state.status == KernelBootstrapStatus.DEGRADED ||
                state.status == KernelBootstrapStatus.FAILED
        }.also { state ->
            if (state.status == KernelBootstrapStatus.FAILED) {
                error("Kernel boot failed during emulator recovery: ${state.failureMessage ?: "unknown"}")
            }
        }
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
        const val ORPHAN_TAG = "v2-cognition-orphan"
        const val SENTINEL_TAG = "private-v1-emulator-recovery"
        const val SENTINEL_SEPARATOR = "|"
    }
}
