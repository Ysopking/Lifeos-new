package app.lifeos.core.runtime.capability

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PrivateGeneratedToolRecoveryContractTest {
    @Test
    fun `approved bounded tool and trial evidence survive clean runtime rehydration without activation`() = runTest {
        val now = Instant.parse("2026-09-11T00:00:00Z")
        val durableState = MemoryStateRepository()
        val durableArtifacts = MemoryArtifactRepository()
        val requestPhotons = linkedMapOf<PhotonId, Photon>()
        val firstCapabilities = CapabilityRegistry()
        val firstTools = GeneratedToolRegistry(durableState = durableState, now = { now })
        val firstTrials = GeneratedToolTrialLedger(durableState = durableState)
        val firstLifecycle = GeneratedToolLifecycleCoordinator(
            tools = firstTools,
            trialLedger = firstTrials,
            capabilityRegistry = firstCapabilities,
        )
        val buildCatalog = PrivateToolBuildCatalog()
        val workshop = ToolWorkshopCoordinator(
            specificationBuilder = PrivateToolSpecificationBuilder(),
            designer = PrivateToolDesigner(),
            implementationEngine = PrivateToolImplementationEngine(),
            buildRunner = PrivateToolBuildRunner(buildCatalog),
            testRunner = PrivateToolTestRunner(buildCatalog),
            securityValidator = PrivateToolSecurityValidator(),
            capabilityVerifier = PrivateGeneratedCapabilityVerifier(buildCatalog),
            registry = firstTools,
            now = { now },
            newToolId = { TOOL_ID },
            artifactRepository = durableArtifacts,
        )
        val genesis = GeneratedToolGenesisCoordinator(workshop, firstLifecycle)
        val requestCoordinator = GeneratedToolRequestCoordinator(genesis::generateFor)
        var tick = 0L
        val trialRunner = GeneratedToolTrialRunner(
            tools = firstTools,
            lifecycle = firstLifecycle,
            artifacts = durableArtifacts,
            now = { now },
            nanoTime = {
                tick += 1_000_000L
                tick
            },
        )
        val userAction = GeneratedToolUserActionCoordinator(
            requests = requestCoordinator,
            persist = { photon -> requestPhotons[photon.id] = photon },
            load = requestPhotons::get,
            trialSuite = PrivateGeneratedToolTrialSuite(trialRunner, durableArtifacts),
            now = { now },
        )

        val gap = CapabilityGap(
            requirement = CapabilityRequirement(
                capabilityId = CapabilityId("text.uppercase.local"),
                severity = GapSeverity.BLOCKING,
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("text"),
            ),
            type = CapabilityGapType.CAPABILITY_MISSING,
        )
        val action = userAction.generateExplicitlyApproved(gap)

        assertTrue(action.execution is GeneratedToolRequestExecutionResult.Completed)
        val trialSuite = assertNotNull(action.trials)
        assertTrue(trialSuite.completeAndExpected)
        assertEquals(3, trialSuite.finalStats?.trials)
        assertEquals(GeneratedToolState.TRIAL, firstTools.get(TOOL_ID)?.state)
        assertTrue(firstCapabilities.all(includeUnavailable = true).none { it.providerType == ProviderType.GENERATED_TOOL })

        GeneratedToolArtifactBootVerifier(durableState, durableArtifacts).verify()

        val restoredCapabilities = CapabilityRegistry()
        val restoredTools = GeneratedToolRegistry(durableState = durableState, now = { now })
        val restoredTrials = GeneratedToolTrialLedger(durableState = durableState)
        val report = GeneratedToolBootStateRehydrator(
            repository = durableState,
            tools = restoredTools,
            trialLedger = restoredTrials,
            capabilityRegistry = restoredCapabilities,
        ).rehydrateOrVerify()

        assertEquals(1, report.restoredTools)
        assertEquals(3, report.restoredTrialResults)
        assertEquals(0, report.restoredActiveProviders)
        assertEquals(GeneratedToolState.TRIAL, restoredTools.get(TOOL_ID)?.state)
        val restoredEvidence = restoredTrials.evidence(TOOL_ID)
        assertEquals(3, restoredEvidence.stats.trials)
        assertEquals(3, restoredEvidence.stats.successes)
        assertEquals(3, restoredEvidence.stats.expectedOutputs)
        assertEquals(0, restoredEvidence.stats.safetyViolations)
        assertTrue(restoredCapabilities.all(includeUnavailable = true).none { it.providerType == ProviderType.GENERATED_TOOL })

        val retry = GeneratedToolBootStateRehydrator(
            repository = durableState,
            tools = restoredTools,
            trialLedger = restoredTrials,
            capabilityRegistry = restoredCapabilities,
        ).rehydrateOrVerify()
        assertEquals(report, retry)
    }

    private class MemoryArtifactRepository : GeneratedToolArtifactRepository {
        private val values = linkedMapOf<String, GeneratedToolArtifact>()

        override suspend fun persist(artifact: GeneratedToolArtifact) {
            values[artifact.toolId]?.let { require(it == artifact) }
            values[artifact.toolId] = artifact
        }

        override suspend fun load(toolId: String): GeneratedToolArtifact? = values[toolId]

        override suspend fun loadAll(): List<GeneratedToolArtifact> = values.values.sortedBy { it.toolId }
    }

    private class MemoryStateRepository : GeneratedToolStateRepository {
        private val values = linkedMapOf<String, GeneratedToolPersistentState>()

        override suspend fun loadAll(): List<GeneratedToolPersistentState> =
            values.values.sortedBy { it.record.manifest.toolId }

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) {
            val existing = values[record.manifest.toolId]
            values[record.manifest.toolId] = GeneratedToolPersistentState(
                record = record,
                auditEntries = auditEntries,
                trialEvidence = existing?.trialEvidence
                    ?: GeneratedToolTrialEvidence(record.manifest.toolId, emptyList()),
                promotionReceipt = promotionEvidence?.let(GeneratedToolPromotionReceipt::from)
                    ?: existing?.promotionReceipt,
            )
        }

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) {
            val existing = requireNotNull(values[evidence.toolId])
            values[evidence.toolId] = existing.copy(trialEvidence = evidence)
        }
    }

    private companion object {
        const val TOOL_ID = "generated-uppercase-recovery"
    }
}
