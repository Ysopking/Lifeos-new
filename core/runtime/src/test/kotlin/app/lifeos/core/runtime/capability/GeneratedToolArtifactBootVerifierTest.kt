package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class GeneratedToolArtifactBootVerifierTest {
    private val t0 = Instant.parse("2026-09-10T20:00:00Z")

    @Test
    fun `exact bounded lifecycle and artifact pass boot verification`() = runTest {
        val states = CapturingStateRepository()
        val artifacts = InMemoryArtifactRepository()
        createBoundedTool(states, artifacts)

        GeneratedToolArtifactBootVerifier(states, artifacts).verify()
    }

    @Test
    fun `missing bounded artifact fails boot verification`() = runTest {
        val states = CapturingStateRepository()
        val artifacts = InMemoryArtifactRepository()
        createBoundedTool(states, artifacts)

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolArtifactBootVerifier(states, InMemoryArtifactRepository()).verify()
        }
    }

    @Test
    fun `orphan bounded artifact fails boot verification`() = runTest {
        val artifacts = InMemoryArtifactRepository()
        val program = GeneratedToolProgram(
            toolId = "orphan-tool",
            capabilityId = CapabilityId("text.normalize"),
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
            instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.NORMALIZE_WHITESPACE)),
        )
        artifacts.persist(
            GeneratedToolArtifact.create(
                toolId = program.toolId,
                canonicalProgram = GeneratedToolProgramCodec.encode(program),
                createdAt = t0,
            )
        )

        assertFailsWith<IllegalArgumentException> {
            GeneratedToolArtifactBootVerifier(CapturingStateRepository(), artifacts).verify()
        }
    }

    @Test
    fun `legacy non bounded lifecycle does not require bounded artifact`() = runTest {
        val states = CapturingStateRepository()
        val registry = GeneratedToolRegistry(durableState = states)
        registry.register(
            GeneratedToolRecord(
                manifest = GeneratedToolManifest(
                    toolId = "legacy-tool",
                    sourceCapability = CapabilityId("legacy.capability"),
                    sourceHash = "legacy-source-hash",
                    buildHash = null,
                    permissions = emptySet(),
                    generatedAt = t0,
                ),
                state = GeneratedToolState.GENERATED,
            )
        )

        GeneratedToolArtifactBootVerifier(states, InMemoryArtifactRepository()).verify()
    }

    private suspend fun createBoundedTool(
        states: CapturingStateRepository,
        artifacts: InMemoryArtifactRepository,
    ) {
        val registry = GeneratedToolRegistry(durableState = states)
        val catalog = PrivateToolBuildCatalog()
        ToolWorkshopCoordinator(
            specificationBuilder = PrivateToolSpecificationBuilder(),
            designer = PrivateToolDesigner(),
            implementationEngine = PrivateToolImplementationEngine(),
            buildRunner = PrivateToolBuildRunner(catalog),
            testRunner = PrivateToolTestRunner(catalog),
            securityValidator = PrivateToolSecurityValidator(),
            capabilityVerifier = PrivateGeneratedCapabilityVerifier(catalog),
            registry = registry,
            now = { t0 },
            newToolId = { "bounded-tool" },
            artifactRepository = artifacts,
        ).generate(
            CapabilityGap(
                requirement = CapabilityRequirement(
                    capabilityId = CapabilityId("text.normalize"),
                    requiredInputs = setOf("text"),
                    requiredOutputs = setOf("normalized-text"),
                ),
                type = CapabilityGapType.CAPABILITY_MISSING,
            )
        )
    }

    private class InMemoryArtifactRepository : GeneratedToolArtifactRepository {
        private val values = linkedMapOf<String, GeneratedToolArtifact>()

        override suspend fun persist(artifact: GeneratedToolArtifact) {
            values[artifact.toolId]?.let { require(it == artifact) }
            values[artifact.toolId] = artifact
        }

        override suspend fun load(toolId: String): GeneratedToolArtifact? = values[toolId]

        override suspend fun loadAll(): List<GeneratedToolArtifact> = values.values.sortedBy { it.toolId }
    }

    private class CapturingStateRepository : GeneratedToolStateRepository {
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
}
