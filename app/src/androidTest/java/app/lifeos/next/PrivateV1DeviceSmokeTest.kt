package app.lifeos.next

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.field.EncryptedFieldSnapshotRepository
import app.lifeos.core.data.task.EncryptedTaskRepository
import app.lifeos.core.data.thought.EncryptedFieldThoughtGraphProjectionOutboxRepository
import app.lifeos.core.data.thought.EncryptedThoughtGraphDeltaRepository
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
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
import app.lifeos.core.runtime.field.DefaultPhotonFieldRequestFactory
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionCoordinator
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionEnvelope
import app.lifeos.core.runtime.field.FieldThoughtGraphProjector
import app.lifeos.core.runtime.thought.DurableThoughtGraph
import app.lifeos.core.runtime.thought.ThoughtGraphDelta
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphProvenance
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import app.lifeos.next.kernel.KernelBootstrapState
import app.lifeos.next.kernel.KernelBootstrapStatus
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android/Keystore recovery contract for private generated tools, durable cognition and V3.
 *
 * The emulator workflow invokes seed first, force-stops and cold-starts the target app, then invokes
 * recovery. The second process must recover generated-tool evidence, cognition state, exact
 * ThoughtMatrix state, append-only ThoughtGraph history, and a committed FieldSnapshot projection
 * whose graph write was deliberately omitted before the process kill.
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
            val preKillV2 = app.kernel.matrix.v2Snapshot()
            assertTrue(
                "Sentinel must be durable in the v2 ThoughtMatrix before process kill",
                preKillV2.nodes.any {
                    it.photonId == sentinel.id && it.sourceRevision == sentinel.revision
                },
            )
            val matrixVault = thoughtMatrixVaultFile()
            assertTrue(
                "ThoughtMatrix encrypted vault must exist before process kill",
                matrixVault.isFile && matrixVault.length() > 0L,
            )

            val graphRepository = EncryptedThoughtGraphDeltaRepository(instrumentation.targetContext)
            val graph = DurableThoughtGraph(graphRepository)
            graph.rehydrate(V3_CAPTURED_AT)
            val graphDelta = v3RecoveryDelta()
            val graphApply = graph.append(graphDelta, V3_CAPTURED_AT)
            assertTrue(graphDelta.id in graphApply.state.appliedDeltaIds)
            assertTrue(graphRepository.loadReport().deltas.any { it.id == graphDelta.id })
            assertTrue(
                "ThoughtGraph encrypted append-only delta vault must exist before process kill",
                thoughtGraphVaultFiles().isNotEmpty() && thoughtGraphVaultFiles().all { it.length() > 0L },
            )

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

            // Simulate the V3 projection crash window: the immutable projection intent and its
            // exact authoritative FieldSnapshot are durable, but the ThoughtGraph delta is not.
            // The workflow force-stops this process after the seed method returns.
            val projection = v3ProjectionRecoveryFixture()
            val projectionOutbox =
                EncryptedFieldThoughtGraphProjectionOutboxRepository(instrumentation.targetContext)
            val projectionSnapshots = EncryptedFieldSnapshotRepository(instrumentation.targetContext)
            projectionOutbox.save(projection.envelope)
            projectionSnapshots.save(projection.snapshot)
            assertTrue(
                "Projection outbox must be fully readable before process kill",
                projectionOutbox.loadReport().unreadableEntries.isEmpty(),
            )
            assertEquals(projection.snapshot, projectionSnapshots.load(projection.snapshot.id))
            assertNull(
                "Seed must leave projection graph delta unwritten so cold boot proves reconciliation",
                graphRepository.load(projection.envelope.delta.id),
            )
            assertTrue(
                "Encrypted projection outbox file must exist before process kill",
                fieldProjectionOutboxFiles().any { it.length() > 0L },
            )
        }
    }

    @Test
    fun assertRecoveredRuntimeAndToolEvidence() {
        runBlocking {
            val boot = awaitBoot()
            assertTrue("Kernel must recover after target process cold restart", boot.ready)

            val graphRepository = EncryptedThoughtGraphDeltaRepository(instrumentation.targetContext)
            val graphLoad = graphRepository.loadReport()
            assertTrue("ThoughtGraph delta vault must remain fully readable", graphLoad.unreadableEntries.isEmpty())
            val observedDeltaIds = graphLoad.deltas.map { it.id }.toSet()
            val projection = v3ProjectionRecoveryFixture()
            assertTrue(graphLoad.deltas.any { it.id == v3RecoveryDelta().id })
            assertTrue(
                "Cold-start boot must materialize the committed projection outbox delta",
                graphLoad.deltas.any { it.id == projection.envelope.delta.id },
            )

            val projectionOutbox =
                EncryptedFieldThoughtGraphProjectionOutboxRepository(instrumentation.targetContext)
            val projectionOutboxLoad = projectionOutbox.loadReport()
            assertTrue(projectionOutboxLoad.unreadableEntries.isEmpty())
            assertTrue(projectionOutboxLoad.envelopes.any { it.id == projection.envelope.id })
            val projectionSnapshots = EncryptedFieldSnapshotRepository(instrumentation.targetContext)
            val committedSnapshot = projectionSnapshots.load(projection.snapshot.id)
            assertNotNull("Projection FieldSnapshot must survive process kill", committedSnapshot)
            assertEquals(
                projection.snapshot.contentFingerprint(),
                committedSnapshot!!.contentFingerprint(),
            )

            val restoredGraph = DurableThoughtGraph(graphRepository)
            val graphRestore = restoredGraph.rehydrate(V3_CAPTURED_AT)
            assertTrue(graphRestore.restoredDeltaCount >= graphLoad.deltas.size)
            assertTrue(graphRestore.snapshot.appliedDeltaIds.containsAll(observedDeltaIds))
            assertEquals(graphRestore.restoredDeltaCount.toLong(), graphRestore.revision)
            assertTrue(graphRestore.snapshot.appliedDeltaIds.contains(v3RecoveryDelta().id))
            assertTrue(graphRestore.snapshot.appliedDeltaIds.contains(projection.envelope.delta.id))
            assertEquals(
                V3_GRAPH_SUMMARY,
                graphRestore.snapshot.activeNodes.single {
                    it.provenance.sourceId == V3_GRAPH_SOURCE_ID
                }.summary,
            )
            val recoveredHistoryFingerprint = graphRestore.snapshot.historyFingerprint
            val revisionBeforeReplay = restoredGraph.state.value.revision
            val graphReplay = restoredGraph.append(
                v3RecoveryDelta().copy(observedAt = V3_OBSERVED_AT.plusSeconds(30)),
                V3_CAPTURED_AT.plusSeconds(30),
            )
            assertTrue("Cold-start graph replay must be idempotent", graphReplay.replayed)
            assertEquals(revisionBeforeReplay, graphReplay.state.revision)
            assertEquals(
                recoveredHistoryFingerprint,
                restoredGraph.snapshot(V3_CAPTURED_AT.plusSeconds(30)).historyFingerprint,
            )
            val afterReplayLoad = graphRepository.loadReport()
            assertTrue(afterReplayLoad.unreadableEntries.isEmpty())
            assertEquals(1, afterReplayLoad.deltas.count { it.id == v3RecoveryDelta().id })

            val projectionCoordinator = FieldThoughtGraphProjectionCoordinator(
                outbox = projectionOutbox,
                snapshots = projectionSnapshots,
                graph = restoredGraph,
            )
            val projectionReplayRevision = restoredGraph.state.value.revision
            assertTrue(projectionCoordinator.materialize(projection.envelope))
            assertEquals(
                "Targeted outbox replay must not duplicate an already-applied graph delta",
                projectionReplayRevision,
                restoredGraph.state.value.revision,
            )

            val sentinel = app.kernel.photonStore.loadAll()
                .singleOrNull { SENTINEL_TAG in it.tags }
            assertNotNull("Cold restart must preserve encrypted recovery sentinel", sentinel)
            sentinel!!

            // This assertion occurs before any re-ingest in the recovered process. Because boot
            // ThoughtMatrix warmup is intentionally non-reprojecting, success proves that module
            // rehydration restored the encrypted matrix state written before the process kill.
            withTimeout(BOOT_TIMEOUT_MS) {
                app.kernel.matrix.state.first { it.nodes[sentinel.id]?.revision == sentinel.revision }
            }
            val restoredV2 = app.kernel.matrix.v2Snapshot()
            assertTrue(
                "Cold restart must restore the sentinel into v2 ThoughtMatrix without re-ingest",
                restoredV2.nodes.any {
                    it.photonId == sentinel.id && it.sourceRevision == sentinel.revision
                },
            )
            assertTrue(
                "ThoughtMatrix encrypted vault must remain readable after cold restart",
                thoughtMatrixVaultFile().isFile && thoughtMatrixVaultFile().length() > 0L,
            )

            assertSingleCognitiveTask(sentinel)
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

    private fun v3ProjectionRecoveryFixture(): V3ProjectionRecoveryFixture {
        val photon = Photon(
            id = PhotonId(V3_PROJECTION_PHOTON_ID),
            revision = 1,
            content = "committed field truth survives projection crash window",
            confidence = 0.93,
            semanticMass = 1.1,
            energy = 0.82,
            provenance = Provenance(
                source = "v3-field-projection-recovery",
                actor = "PrivateV1DeviceSmokeTest",
                createdAt = V3_PROJECTION_AT,
            ),
            tags = setOf(V3_PROJECTION_TAG),
        )
        val request = DefaultPhotonFieldRequestFactory().create(photon)
        val result = FieldConvergenceEngine().converge(request)
        return V3ProjectionRecoveryFixture(
            envelope = FieldThoughtGraphProjector().project(photon, request, result),
            snapshot = result.snapshot,
        )
    }

    private fun v3RecoveryDelta(): ThoughtGraphDelta {
        val provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.SYSTEM,
            sourceId = V3_GRAPH_SOURCE_ID,
            sourceRevision = 1,
            sourceFingerprint = V3_GRAPH_SOURCE_FINGERPRINT,
            origin = "android-emulator-recovery",
            actor = "PrivateV1DeviceSmokeTest",
            createdAt = V3_SOURCE_AT,
        )
        val node = ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.PHOTON,
            semanticKey = "v3-recovery",
            summary = V3_GRAPH_SUMMARY,
            confidence = 0.91,
            authority = 0.88,
            validity = TemporalValidity.at(V3_SOURCE_AT),
            provenance = provenance,
            attributes = mapOf("contract" to "v3-delta-vault-cold-restart"),
        )
        return ThoughtGraphDelta.create(
            sourceKey = "android-emulator:v3-recovery",
            sourceRevision = 1,
            nodeVersions = listOf(node),
            observedAt = V3_OBSERVED_AT,
        )
    }

    private fun thoughtMatrixVaultFile() =
        instrumentation.targetContext.filesDir
            .resolve("thought-matrix-state-vault")
            .resolve("latest.tmatrix")

    private fun thoughtGraphVaultFiles() =
        instrumentation.targetContext.filesDir
            .resolve("thought-graph-delta-vault")
            .listFiles()
            ?.filter { it.name.endsWith(".tgdelta") }
            .orEmpty()

    private fun fieldProjectionOutboxFiles() =
        instrumentation.targetContext.filesDir
            .resolve("field-thought-graph-projection-outbox")
            .listFiles()
            ?.filter { it.name.endsWith(".fgprojection") }
            .orEmpty()

    private suspend fun cognitiveTasks(photon: Photon) =
        EncryptedTaskRepository(instrumentation.targetContext).loadReport().also {
            assertTrue("Task vault must remain readable", it.unreadableEntries.isEmpty())
        }.tasks.filter {
            it.type == TaskType.PROCESS_PHOTON &&
                it.inputPhotonRevisions[photon.id] == photon.revision
        }

    private suspend fun assertSingleCognitiveTask(photon: Photon) {
        val tasks = cognitiveTasks(photon)
        // REPROCESS_PHOTON is a distinct, outcome-triggered reevaluation, not duplicate ingestion.
        assertEquals("Each revision must have one initial task; found " +
            tasks.joinToString { "${it.id.value}:${it.idempotencyKey}" }, 1, tasks.size)
        assertEquals(
            "cognition:photon:${photon.id.value}:revision:${photon.revision}" +
                ":photon:${photon.id.value}:revision:${photon.revision}:pipeline:1",
            tasks.single().idempotencyKey,
        )
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

    private data class V3ProjectionRecoveryFixture(
        val envelope: FieldThoughtGraphProjectionEnvelope,
        val snapshot: app.lifeos.core.field.FieldSnapshot,
    )

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
        const val ORPHAN_TAG = "v2-cognition-orphan"
        const val SENTINEL_TAG = "private-v1-emulator-recovery"
        const val SENTINEL_SEPARATOR = "|"
        const val V3_GRAPH_SOURCE_ID = "system:v3-android-recovery"
        const val V3_GRAPH_SOURCE_FINGERPRINT = "v3-android-recovery-source-v1"
        const val V3_GRAPH_SUMMARY = "durable V3 graph survives Android process kill"
        const val V3_PROJECTION_PHOTON_ID = "v3-field-projection-recovery-photon"
        const val V3_PROJECTION_TAG = "v3-field-projection-recovery"
        val V3_SOURCE_AT: Instant = Instant.parse("2026-09-11T06:45:00Z")
        val V3_OBSERVED_AT: Instant = Instant.parse("2026-09-11T06:45:01Z")
        val V3_CAPTURED_AT: Instant = Instant.parse("2026-09-11T06:45:02Z")
        val V3_PROJECTION_AT: Instant = Instant.parse("2026-09-11T06:46:00Z")
    }
}
