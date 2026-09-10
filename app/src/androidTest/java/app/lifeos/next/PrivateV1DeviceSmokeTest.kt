package app.lifeos.next

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.GeneratedToolGenesisResult
import app.lifeos.core.runtime.capability.GeneratedToolRequestExecutionResult
import app.lifeos.core.runtime.capability.GeneratedToolState
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
 * Real Android/Keystore smoke contract for the private-v1 final gate.
 *
 * The CI workflow invokes seed first, force-stops and cold-starts the target app, then invokes
 * recovery. This verifies actual encrypted stores, Application boot, Activity recreation and the
 * generated-tool TRIAL recovery path on an emulator without granting promotion authority.
 */
@RunWith(AndroidJUnit4::class)
class PrivateV1DeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun seedGeneratedToolAndAssertRuntime() = runBlocking {
        val boot = awaitBoot()
        assertTrue("Kernel must complete boot before device smoke", boot.ready)

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

        val status = app.generatedToolStatusReader.snapshot()
        val tool = status.tools.single { it.toolId == genesis.record.manifest.toolId }
        assertEquals(GeneratedToolState.TRIAL, tool.state)
        assertEquals(3, tool.trials)
        assertEquals(3, tool.successes)
        assertEquals(3, tool.expectedOutputs)
        assertEquals(0, tool.safetyViolations)
        assertTrue("TRIAL action must not create ACTIVE provider state", status.activeTools == 0)

        val sentinel = Photon(
            content = "$SENTINEL_PREFIX${tool.toolId}",
            provenance = Provenance("private-v1-device-smoke", "instrumentation"),
            tags = setOf(SENTINEL_TAG),
        )
        val persisted = app.kernel.persistAndIngest(sentinel)
        assertEquals(sentinel, app.kernel.photonStore.load(sentinel.id))
        assertTrue("Smoke sentinel must enter durable cognition", persisted.processingQueued)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun assertRecoveredRuntimeAndToolEvidence() = runBlocking {
        val boot = awaitBoot()
        assertTrue("Kernel must recover after target process cold restart", boot.ready)

        val sentinel = app.kernel.photonStore.loadAll()
            .singleOrNull { SENTINEL_TAG in it.tags }
        assertNotNull("Cold restart must preserve encrypted smoke sentinel", sentinel)
        val toolId = sentinel!!.content.removePrefix(SENTINEL_PREFIX)
        assertTrue("Smoke sentinel must contain generated tool id", toolId.isNotBlank())

        val status = app.generatedToolStatusReader.snapshot()
        val tool = status.tools.single { it.toolId == toolId }
        assertEquals(GeneratedToolState.TRIAL, tool.state)
        assertEquals(3, tool.trials)
        assertEquals(3, tool.successes)
        assertEquals(3, tool.expectedOutputs)
        assertEquals(0, tool.safetyViolations)
        assertEquals("Cold recovery must not activate generated tools", 0, status.activeTools)
        assertTrue(tool.promotionEvidenceId == null)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
        }
    }

    private suspend fun awaitBoot(): KernelBootstrapState = withTimeout(BOOT_TIMEOUT_MS) {
        app.kernel.bootstrapState.first { state ->
            state.status == KernelBootstrapStatus.READY ||
                state.status == KernelBootstrapStatus.DEGRADED ||
                state.status == KernelBootstrapStatus.FAILED
        }.also { state ->
            if (state.status == KernelBootstrapStatus.FAILED) {
                error("Kernel boot failed during device smoke: ${state.failureMessage ?: "unknown"}")
            }
        }
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
        const val SENTINEL_TAG = "private-v1-device-smoke"
        const val SENTINEL_PREFIX = "private-v1-tool:"
    }
}
