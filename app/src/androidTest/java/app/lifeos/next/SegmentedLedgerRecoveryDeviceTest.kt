package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.capability.EncryptedToolWorkshopJobRepository
import app.lifeos.core.data.health.EncryptedSelfHealingRepository
import app.lifeos.core.data.trace.EncryptedDecisionTraceRepository
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.ToolWorkshopJobDefinition
import app.lifeos.core.runtime.capability.ToolWorkshopJobEvent
import app.lifeos.core.runtime.capability.ToolWorkshopJobId
import app.lifeos.core.runtime.capability.ToolWorkshopJobState
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.SelfHealingEvent
import app.lifeos.core.runtime.health.SelfHealingEventType
import app.lifeos.core.runtime.health.SelfHealingIncidentId
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceId
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentedLedgerRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-09-19T17:00:00Z")

    @Test
    fun toolWorkshopMissingHeadRecoversFromDurableSegments() = runBlocking {
        withIsolatedFiles("workshop-missing-head") { context, root ->
            val repository = EncryptedToolWorkshopJobRepository(context)
            val first = workshopEvent(revision = 1L, state = ToolWorkshopJobState.REQUESTED)
            assertTrue(repository.append(0L, first))

            deleteAtomic(root.resolve("tool-workshop-job-ledger/head.twj"))

            val reopened = EncryptedToolWorkshopJobRepository(context)
            assertEquals(listOf(1L), reopened.loadReport().events.map { it.revision })
            val second = workshopEvent(
                revision = 2L,
                state = ToolWorkshopJobState.SPECIFIED,
                stageFingerprint = "spec:m215d",
                recordedAt = now.plusSeconds(1),
            )
            assertTrue(reopened.append(1L, second))
            assertEquals(listOf(1L, 2L), reopened.loadReport().events.map { it.revision })
        }
    }

    @Test
    fun selfHealingStaleHeadIsRepairedFromDurableTail() = runBlocking {
        withIsolatedFiles("self-healing-stale-head") { context, root ->
            val repository = EncryptedSelfHealingRepository(context)
            val first = selfHealingEvent(1L, SelfHealingEventType.OPENED)
            assertTrue(repository.append(0L, first))
            val head = root.resolve("self-healing-ledger/head.sheal")
            val staleHead = head.readBytes()

            val second = selfHealingEvent(
                revision = 2L,
                type = SelfHealingEventType.ACTION_PREPARED,
                actionIndex = 0,
                actionId = "action:m215d",
                recordedAt = now.plusSeconds(1),
            )
            assertTrue(repository.append(1L, second))
            head.writeBytes(staleHead)
            File(head.path + ".bak").delete()

            val reopened = EncryptedSelfHealingRepository(context)
            assertEquals(listOf(1L, 2L), reopened.loadReport().events.map { it.revision })
            val third = selfHealingEvent(
                revision = 3L,
                type = SelfHealingEventType.ACTION_FAILED,
                actionIndex = 0,
                actionId = "action:m215d",
                recordedAt = now.plusSeconds(2),
            )
            assertTrue(reopened.append(2L, third))
            assertEquals(3L, reopened.loadReport().events.last().revision)
        }
    }

    @Test
    fun headPastDurableTailFailsClosed() = runBlocking {
        withIsolatedFiles("workshop-head-past-tail") { context, root ->
            val repository = EncryptedToolWorkshopJobRepository(context)
            assertTrue(repository.append(0L, workshopEvent(1L, ToolWorkshopJobState.REQUESTED)))
            assertTrue(
                repository.append(
                    1L,
                    workshopEvent(
                        2L,
                        ToolWorkshopJobState.SPECIFIED,
                        stageFingerprint = "spec:m215d",
                        recordedAt = now.plusSeconds(1),
                    ),
                )
            )

            val secondSegment = root.resolve("tool-workshop-job-ledger/jobs")
                .walkTopDown()
                .single { it.isFile && it.name == "event-00000000000000000002.twj" }
            assertTrue(secondSegment.delete())

            val reopened = EncryptedToolWorkshopJobRepository(context)
            assertTrue(runCatching { reopened.loadReport() }.isFailure)
        }
    }

    @Test
    fun corruptDecisionTraceSegmentBlocksFurtherMutation() = runBlocking {
        withIsolatedFiles("decision-trace-corrupt") { context, root ->
            val repository = EncryptedDecisionTraceRepository(context)
            val id = DecisionTraceId.create("m215d-recovery", "trace")
            val first = DecisionTrace(id = id, revision = 1L, nodes = emptyList(), links = emptyList())
            assertTrue(repository.save(0L, first))

            val segment = root.resolve("decision-trace-ledger/traces")
                .walkTopDown()
                .single { it.isFile && it.name.endsWith(".dtrace") }
            val bytes = segment.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            segment.writeBytes(bytes)
            File(segment.path + ".bak").delete()

            val reopened = EncryptedDecisionTraceRepository(context)
            val report = reopened.loadReport()
            assertTrue(report.traces.isEmpty())
            assertEquals(1, report.unreadableEntries.size)
            assertTrue(
                runCatching {
                    reopened.save(
                        1L,
                        DecisionTrace(id = id, revision = 2L, nodes = emptyList(), links = emptyList()),
                    )
                }.isFailure
            )
        }
    }

    private fun workshopEvent(
        revision: Long,
        state: ToolWorkshopJobState,
        stageFingerprint: String? = null,
        recordedAt: Instant = now,
    ): ToolWorkshopJobEvent {
        val capabilityId = CapabilityId("m215d.recovery")
        val jobId = ToolWorkshopJobId.create(
            sourcePhotonId = "m215d-photon",
            sourceRevision = 1L,
            capabilityId = capabilityId,
            severity = GapSeverity.BLOCKING,
            gapType = CapabilityGapType.CAPABILITY_MISSING,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("text"),
            candidateProviderIds = emptyList(),
            policyVersion = "m215d-policy-v1",
            workshopVersion = "m215d-workshop-v1",
        )
        val definition = ToolWorkshopJobDefinition(
            id = jobId,
            sourceRequestId = "m215d-request",
            sourcePhotonId = "m215d-photon",
            sourceRevision = 1L,
            capabilityId = capabilityId,
            severity = GapSeverity.BLOCKING,
            gapType = CapabilityGapType.CAPABILITY_MISSING,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("text"),
            candidateProviderIds = emptyList(),
            policyVersion = "m215d-policy-v1",
            workshopVersion = "m215d-workshop-v1",
            createdAt = now,
        )
        return ToolWorkshopJobEvent(
            revision = revision,
            definition = definition,
            state = state,
            recordedAt = recordedAt,
            stageFingerprint = stageFingerprint,
        )
    }

    private fun selfHealingEvent(
        revision: Long,
        type: SelfHealingEventType,
        actionIndex: Int? = null,
        actionId: String? = null,
        recordedAt: Instant = now,
    ): SelfHealingEvent = SelfHealingEvent(
        revision = revision,
        incidentId = SelfHealingIncidentId("self-healing-incident:m215d-recovery"),
        nodeId = HealthNodeId("m215d-recovery-node"),
        planFingerprint = "m215d-plan",
        type = type,
        recordedAt = recordedAt,
        actionIndex = actionIndex,
        actionId = actionId,
    )

    private fun deleteAtomic(file: File) {
        file.delete()
        File(file.path + ".bak").delete()
        File(file.path + ".new").delete()
    }

    private suspend fun withIsolatedFiles(
        suffix: String,
        block: suspend (Context, File) -> Unit,
    ) {
        val root = instrumentation.targetContext.cacheDir.resolve(
            "m215d-ledger-recovery-" + suffix + "-" + System.nanoTime()
        )
        assertFalse(root.exists())
        assertTrue(root.mkdirs())
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = root
        }
        try {
            block(context, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
