package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.capability.EncryptedGeneratedToolArtifactRepository
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.data.capability.EncryptedToolWorkshopJobRepository
import app.lifeos.core.data.capability.EncryptedToolWorkshopStageArtifactRepository
import app.lifeos.core.data.escalation.EncryptedEscalationRepository
import app.lifeos.core.data.health.EncryptedSelfHealingRepository
import app.lifeos.core.data.trace.EncryptedDecisionTraceRepository
import app.lifeos.core.data.world.EncryptedWorldEquationSpecRepository
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.GeneratedToolArtifact
import app.lifeos.core.runtime.capability.GeneratedToolAuditAction
import app.lifeos.core.runtime.capability.GeneratedToolAuditEntry
import app.lifeos.core.runtime.capability.GeneratedToolInstruction
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolOpcode
import app.lifeos.core.runtime.capability.GeneratedToolProgram
import app.lifeos.core.runtime.capability.GeneratedToolProgramCodec
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ToolWorkshopJobDefinition
import app.lifeos.core.runtime.capability.ToolWorkshopJobEvent
import app.lifeos.core.runtime.capability.ToolWorkshopJobId
import app.lifeos.core.runtime.capability.ToolWorkshopJobState
import app.lifeos.core.runtime.capability.ToolWorkshopStageArtifact
import app.lifeos.core.runtime.escalation.EscalationId
import app.lifeos.core.runtime.escalation.EscalationRecord
import app.lifeos.core.runtime.escalation.EscalationRecordType
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.SelfHealingEvent
import app.lifeos.core.runtime.health.SelfHealingEventType
import app.lifeos.core.runtime.health.SelfHealingIncidentId
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentedRepositoryPathBindingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-09-18T18:20:00Z")


    @Test
    fun worldEquationSpecVaultRejectsValidCiphertextAtWrongVersionPath() = runBlocking {
        withIsolatedFiles("world-equation-spec") { context, root ->
            val repository = EncryptedWorldEquationSpecRepository(context)
            val spec = CognitiveWorldEquationProfile().spec
            repository.putIfAbsent(spec)

            val vault = root.resolve("world-equation-spec-vault")
            val original = vault.listFiles().orEmpty().single { it.name.endsWith(".weqspec") }
            val relocated = vault.resolve(
                "0000000000000000000000000000000000000000000000000000000000000000.weqspec"
            )
            original.copyTo(relocated)
            assertTrue(original.delete())

            val report = repository.loadReport()
            assertTrue(report.specs.isEmpty())
            assertEquals(listOf(relocated.name), report.unreadableEntries)
            assertTrue(runCatching { repository.load(spec.version) }.getOrNull() == null)
        }
    }

    @Test
    fun generatedToolArtifactLoadAllRejectsValidCiphertextAtWrongPath() = runBlocking {
        withIsolatedFiles("generated-artifact") { context, root ->
            val repository = EncryptedGeneratedToolArtifactRepository(context)
            val toolId = "path-bound-tool"
            val program = GeneratedToolProgram(
                toolId = toolId,
                capabilityId = CapabilityId("path-bound-capability"),
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("text"),
                instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.TRIM)),
            )
            repository.persist(
                GeneratedToolArtifact.create(
                    toolId = toolId,
                    canonicalProgram = GeneratedToolProgramCodec.encode(program),
                    createdAt = now,
                )
            )
            val records = root.resolve("generated-tool-artifact-vault/records")
            val original = records.listFiles().orEmpty().single { it.name.endsWith(".tool") }
            val relocated = records.resolve("0000000000000000000000000000000000000000000000000000000000000000.tool")
            original.copyTo(relocated)
            assertTrue(original.delete())
            assertTrue(runCatching { repository.loadAll() }.isFailure)
        }
    }

    @Test
    fun generatedToolStateLoadAllRejectsValidCiphertextAtWrongPath() = runBlocking {
        withIsolatedFiles("generated-state") { context, root ->
            val repository = EncryptedGeneratedToolStateRepository(context)
            val toolId = "path-bound-state-tool"
            val manifest = GeneratedToolManifest(
                toolId = toolId,
                sourceCapability = CapabilityId("path-bound-state-capability"),
                sourceHash = "source-hash",
                buildHash = null,
                permissions = emptySet(),
                generatedAt = now,
            )
            val record = GeneratedToolRecord(manifest = manifest, state = GeneratedToolState.GENERATED)
            val afterFingerprint = StableFieldIds.fingerprint(
                "generated-tool-audit-record/v1",
                manifest.toolId,
                manifest.sourceCapability.value,
                manifest.sourceHash,
                manifest.buildHash.orEmpty(),
                manifest.generatedAt.toString(),
                record.state.name,
                record.verificationConfidence.toString(),
                record.lastMessage.orEmpty(),
                record.promotionEvidenceId.orEmpty(),
            )
            val audit = GeneratedToolAuditEntry(
                toolId = toolId,
                action = GeneratedToolAuditAction.REGISTERED,
                fromState = null,
                toState = GeneratedToolState.GENERATED,
                beforeRecordFingerprint = null,
                afterRecordFingerprint = afterFingerprint,
                occurredAt = now,
            )
            repository.persistLifecycle(record, listOf(audit), null)

            val records = root.resolve("generated-tool-state-vault/records")
            val original = records.listFiles().orEmpty().single { it.name.endsWith(".toolstate") }
            val relocated = records.resolve("0000000000000000000000000000000000000000000000000000000000000000.toolstate")
            original.copyTo(relocated)
            assertTrue(original.delete())
            assertTrue(runCatching { repository.loadAll() }.isFailure)
        }
    }

    @Test
    fun workshopStageLoadAllRejectsValidCiphertextAtWrongStagePath() = runBlocking {
        withIsolatedFiles("workshop-stage") { context, root ->
            val repository = EncryptedToolWorkshopStageArtifactRepository(context)
            val jobId = ToolWorkshopJobId("tool-workshop:path-binding-stage")
            repository.persist(
                ToolWorkshopStageArtifact(
                    jobId = jobId,
                    stage = ToolWorkshopJobState.SPECIFIED,
                    payload = "stage-payload",
                    createdAt = now,
                )
            )
            val jobDirectory = root.resolve("tool-workshop-stage-artifacts/records")
                .resolve(sha256(jobId.value))
            val original = jobDirectory.listFiles().orEmpty().single { it.name.endsWith(".twa") }
            val relocated = jobDirectory.resolve("2-designed.twa")
            original.copyTo(relocated)
            assertTrue(original.delete())
            assertTrue(runCatching { repository.loadAll(jobId) }.isFailure)
        }
    }

    @Test
    fun workshopLedgerRejectsValidCiphertextInWrongJobDirectory() = runBlocking {
        withIsolatedFiles("workshop-ledger") { context, root ->
            val repository = EncryptedToolWorkshopJobRepository(context)
            val capabilityId = CapabilityId("path-binding-workshop-capability")
            val jobId = ToolWorkshopJobId.create(
                sourcePhotonId = "path-binding-photon",
                sourceRevision = 1L,
                capabilityId = capabilityId,
                severity = GapSeverity.BLOCKING,
                gapType = CapabilityGapType.CAPABILITY_MISSING,
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("text"),
                candidateProviderIds = emptyList(),
                policyVersion = "path-binding-policy-v1",
                workshopVersion = "path-binding-workshop-v1",
            )
            val definition = ToolWorkshopJobDefinition(
                id = jobId,
                sourceRequestId = "path-binding-request",
                sourcePhotonId = "path-binding-photon",
                sourceRevision = 1L,
                capabilityId = capabilityId,
                severity = GapSeverity.BLOCKING,
                gapType = CapabilityGapType.CAPABILITY_MISSING,
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("text"),
                candidateProviderIds = emptyList(),
                policyVersion = "path-binding-policy-v1",
                workshopVersion = "path-binding-workshop-v1",
                createdAt = now,
            )
            val event = ToolWorkshopJobEvent(
                revision = 1L,
                definition = definition,
                state = ToolWorkshopJobState.REQUESTED,
                recordedAt = now,
            )
            assertTrue(repository.append(0L, event))

            val rootDirectory = root.resolve("tool-workshop-job-ledger")
            val original = rootDirectory.resolve("jobs").walkTopDown()
                .single { it.isFile && it.name.endsWith(".twj") }
            val wrongDirectory = rootDirectory.resolve("jobs").resolve("0000000000000000000000000000000000000000000000000000000000000000")
            assertTrue(wrongDirectory.mkdirs())
            val relocated = wrongDirectory.resolve(original.name)
            original.copyTo(relocated)
            assertTrue(original.delete())

            val report = repository.loadReport()
            assertTrue(report.events.isEmpty())
            assertEquals(listOf(relocated.relativeTo(rootDirectory).path), report.unreadableEntries)
            assertTrue(
                runCatching {
                    repository.append(1L, event.copy(revision = 2L, recordedAt = now.plusSeconds(1)))
                }.isFailure
            )
        }
    }

    @Test
    fun selfHealingLedgerRejectsValidCiphertextInWrongIncidentDirectory() = runBlocking {
        withIsolatedFiles("self-healing") { context, root ->
            val repository = EncryptedSelfHealingRepository(context)
            val event = SelfHealingEvent(
                revision = 1L,
                incidentId = SelfHealingIncidentId("self-healing-incident:path-binding"),
                nodeId = HealthNodeId("path-binding-node"),
                planFingerprint = "path-binding-plan",
                type = SelfHealingEventType.OPENED,
                recordedAt = now,
            )
            assertTrue(repository.append(0L, event))

            val rootDirectory = root.resolve("self-healing-ledger")
            val original = rootDirectory.resolve("incidents").walkTopDown()
                .single { it.isFile && it.name.endsWith(".sheal") }
            val wrongDirectory = rootDirectory.resolve("incidents").resolve("0000000000000000000000000000000000000000000000000000000000000000")
            assertTrue(wrongDirectory.mkdirs())
            val relocated = wrongDirectory.resolve(original.name)
            original.copyTo(relocated)
            assertTrue(original.delete())

            val report = repository.loadReport()
            assertTrue(report.events.isEmpty())
            assertEquals(listOf(relocated.relativeTo(rootDirectory).path), report.unreadableEntries)
            assertTrue(
                runCatching {
                    repository.append(1L, event.copy(revision = 2L, recordedAt = now.plusSeconds(1)))
                }.isFailure
            )
        }
    }

    @Test
    fun escalationLedgerRejectsValidCiphertextInWrongEscalationDirectory() = runBlocking {
        withIsolatedFiles("escalation-ledger") { context, root ->
            val repository = EncryptedEscalationRepository(context)
            val record = EscalationRecord(
                revision = 1L,
                escalationId = EscalationId("escalation:" + "c".repeat(64)),
                nodeId = HealthNodeId("path-bound-escalation-node"),
                triggerFingerprint = "path-bound-escalation-trigger",
                type = EscalationRecordType.OPENED,
                recordedAt = now,
            )
            assertTrue(repository.append(0L, record))

            val rootDirectory = root.resolve("escalation-ledger")
            val original = rootDirectory.resolve("records").walkTopDown()
                .single { it.isFile && it.name.endsWith(".escalation") }
            val wrongDirectory = rootDirectory.resolve("records")
                .resolve("0000000000000000000000000000000000000000000000000000000000000000")
            assertTrue(wrongDirectory.mkdirs())
            val relocated = wrongDirectory.resolve(original.name)
            original.copyTo(relocated)
            assertTrue(original.delete())

            val report = repository.loadReport()
            assertTrue(report.records.isEmpty())
            assertEquals(listOf(relocated.relativeTo(rootDirectory).path), report.unreadableEntries)
            assertTrue(
                runCatching {
                    repository.append(
                        1L,
                        record.copy(revision = 2L, recordedAt = now.plusSeconds(1))
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun decisionTraceLoadReportRejectsValidCiphertextInWrongTraceDirectory() = runBlocking {
        withIsolatedFiles("decision-trace") { context, root ->
            val repository = EncryptedDecisionTraceRepository(context)
            val trace = DecisionTrace(
                id = DecisionTraceId.create("path-binding-test", "root"),
                revision = 1L,
                nodes = emptyList(),
                links = emptyList(),
            )
            assertTrue(repository.save(0L, trace))

            val rootDirectory = root.resolve("decision-trace-ledger")
            val original = rootDirectory.resolve("traces").walkTopDown()
                .single { it.isFile && it.name.endsWith(".dtrace") }
            val wrongDirectory = rootDirectory.resolve("traces").resolve("0000000000000000000000000000000000000000000000000000000000000000")
            assertTrue(wrongDirectory.mkdirs())
            val relocated = wrongDirectory.resolve(original.name)
            original.copyTo(relocated)
            assertTrue(original.delete())

            val report = repository.loadReport()
            assertTrue(report.traces.isEmpty())
            assertEquals(listOf(relocated.relativeTo(rootDirectory).path), report.unreadableEntries)
        }
    }

    private suspend fun withIsolatedFiles(
        suffix: String,
        block: suspend (Context, File) -> Unit,
    ) {
        val root = instrumentation.targetContext.cacheDir.resolve(
            "b145-segment-path-" + suffix + "-" + System.nanoTime()
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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
