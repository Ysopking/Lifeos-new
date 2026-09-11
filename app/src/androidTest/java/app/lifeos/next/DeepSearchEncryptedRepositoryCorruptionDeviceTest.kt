package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.deepsearch.EncryptedDeepSearchCheckpointRepository
import app.lifeos.core.data.deepsearch.EncryptedDeepSearchMissionRepository
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.deepsearch.DeepSearchBudget
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointLoadReport
import app.lifeos.core.runtime.deepsearch.DeepSearchFrontierSnapshot
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionDefinition
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEvent
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEventType
import app.lifeos.core.runtime.deepsearch.DeepSearchPlannerCheckpoint
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchStoredCheckpoint
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android Keystore/device proof that V12 encrypted DeepSearch stores fail closed on corruption. */
@RunWith(AndroidJUnit4::class)
class DeepSearchEncryptedRepositoryCorruptionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-09-11T20:30:00Z")

    @Test
    fun missionLedgerReportsCorruptionAndRefusesAppend() = runBlocking {
        withIsolatedFiles("mission") { context, root ->
            val repository = EncryptedDeepSearchMissionRepository(context)
            val definition = definition()
            val planned = DeepSearchMissionEvent(
                revision = 1L,
                missionId = definition.id,
                type = DeepSearchMissionEventType.PLANNED,
                recordedAt = now,
                definition = definition,
            )
            assertTrue(repository.append(0L, planned))

            val vault = root.resolve("deep-search-v2/missions.dsmission")
            assertTrue("Mission vault must exist before corruption", vault.isFile && vault.length() > 0L)
            corruptAtomicFile(vault)

            val report = repository.loadReport()
            assertTrue(report.events.isEmpty())
            assertEquals(listOf("missions.dsmission"), report.unreadableEntries)

            val blocked = runCatching {
                repository.append(
                    expectedRevision = 1L,
                    event = DeepSearchMissionEvent(
                        revision = 2L,
                        missionId = definition.id,
                        type = DeepSearchMissionEventType.BLOCKED,
                        recordedAt = now.plusSeconds(1),
                        detail = "corruption-probe",
                    ),
                )
            }
            assertTrue("Corrupt mission history must block append", blocked.isFailure)
        }
    }

    @Test
    fun checkpointStoreReportsCorruptionAndRefusesCompareAndSet() = runBlocking {
        withIsolatedFiles("checkpoint") { context, root ->
            val definition = definition()
            val repository = EncryptedDeepSearchCheckpointRepository(context)
            val checkpoint = nonTerminalCheckpoint()
            val first = DeepSearchStoredCheckpoint(
                missionId = definition.id,
                revision = 1L,
                checkpoint = checkpoint,
                recordedAt = now,
            )
            assertTrue(repository.compareAndSet(definition.id, 0L, first))

            val vault = root.resolve("deep-search-v2/checkpoints/${definition.id.value}.dscp")
            assertTrue("Checkpoint vault must exist before corruption", vault.isFile && vault.length() > 0L)
            corruptAtomicFile(vault)

            val report: DeepSearchCheckpointLoadReport = repository.load(definition.id)
            assertEquals(null, report.value)
            assertEquals(listOf(vault.name), report.unreadableEntries)

            val blocked = runCatching {
                repository.compareAndSet(
                    missionId = definition.id,
                    expectedRevision = 1L,
                    updated = first.copy(
                        revision = 2L,
                        recordedAt = now.plusSeconds(1),
                    ),
                )
            }
            assertTrue("Corrupt checkpoint history must block compare-and-set", blocked.isFailure)
        }
    }

    private suspend fun withIsolatedFiles(
        suffix: String,
        block: suspend (Context, File) -> Unit,
    ) {
        val root = instrumentation.targetContext.cacheDir.resolve(
            "v12-deepsearch-corruption-$suffix-${System.nanoTime()}"
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

    private fun corruptAtomicFile(target: File) {
        File("${target.path}.bak").delete()
        target.writeBytes(byteArrayOf(0x13, 0x37, 0x00, 0x7f))
    }

    private fun definition() = DeepSearchMissionDefinition.create(
        goalPhotonId = PhotonId("goal-corruption-test"),
        sourcePhotonId = PhotonId("source-corruption-test"),
        sourceRevision = 1L,
        query = "verify encrypted deep search corruption",
        searchPolicyVersion = "deepsearch-v2-corruption-test",
        sourceScopeIds = setOf("local-test-source"),
        sourceSnapshotFingerprint = "b".repeat(64),
        createdAt = now,
    )

    private fun nonTerminalCheckpoint(): DeepSearchPlannerCheckpoint {
        val request = DeepSearchRequest(
            query = "verify encrypted deep search corruption",
            budget = DeepSearchBudget(
                maxDepth = 2,
                maxBreadth = 2,
                maxWorkUnits = 2,
                maxElapsed = Duration.ofSeconds(2),
            ),
        )
        return DeepSearchPlannerCheckpoint(
            request = request,
            frontier = DeepSearchFrontierSnapshot(
                admittedBranches = emptyList(),
                queuedBranchIds = emptySet(),
                expandedBranchIds = emptySet(),
            ),
            evidence = emptyList(),
            trace = emptyList(),
            workUnitsUsed = 0,
            elapsedMillisUsed = 0L,
            blockedSourceIds = emptySet(),
            failedSourceIds = emptySet(),
            rootExpanded = false,
        )
    }
}
