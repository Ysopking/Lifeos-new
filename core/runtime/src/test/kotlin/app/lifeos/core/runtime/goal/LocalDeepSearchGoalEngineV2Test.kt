package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchBudget
import app.lifeos.core.runtime.deepsearch.DeepSearchCapabilityGate
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointSink
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchFindingDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionDefinition
import app.lifeos.core.runtime.deepsearch.DeepSearchPermissionState
import app.lifeos.core.runtime.deepsearch.DeepSearchPlannerCheckpoint
import app.lifeos.core.runtime.deepsearch.DeepSearchPlannerV2
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchSource
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceAuthorization
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceDescriptor
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceKind
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalDeepSearchGoalEngineV2Test {
    private val at = Instant.parse("2026-09-11T18:00:00Z")

    @Test
    fun `terminal mission checkpoint renders result without planner source authorization or sink writes`() = runTest {
        val evidenceId = PhotonId("evidence-photon")
        val request = DeepSearchRequest(
            query = "lifeos photons",
            budget = DeepSearchBudget(
                maxDepth = 2,
                maxBreadth = 6,
                maxWorkUnits = 16,
                maxElapsed = Duration.ofSeconds(4),
            ),
        )
        var terminalCheckpoint: DeepSearchPlannerCheckpoint? = null
        val originalResult = DeepSearchPlannerV2(now = { at }).search(
            request = request,
            sources = listOf(SingleSource(evidenceId)),
            checkpointSink = DeepSearchCheckpointSink { terminalCheckpoint = it },
        )
        assertEquals(DeepSearchStatus.RESOLVED, originalResult.status)
        val checkpoint = requireNotNull(terminalCheckpoint)

        val hostilePlanner = DeepSearchPlannerV2(
            capabilityGate = object : DeepSearchCapabilityGate {
                override suspend fun authorize(source: DeepSearchSourceDescriptor): DeepSearchSourceAuthorization =
                    error("terminal recovery must not authorize a source")
            },
            now = { at.plusSeconds(1) },
        )
        val engine = LocalDeepSearchGoalEngine(
            planner = hostilePlanner,
            sharedBudgets = null,
        )
        val goalPhotonId = PhotonId("goal-search")
        val source = photon("request", "search: lifeos photons")
        val mission = DeepSearchMissionDefinition.create(
            goalPhotonId = goalPhotonId,
            sourcePhotonId = source.id,
            sourceRevision = source.revision,
            query = "search: lifeos photons",
            searchPolicyVersion = LocalDeepSearchGoalEngine.SEARCH_POLICY_VERSION,
            sourceScopeIds = setOf(LocalDeepSearchGoalEngine.LOCAL_SOURCE_ID),
            sourceSnapshotFingerprint = "a".repeat(64),
            createdAt = at,
        )

        val produced = assertIs<LocalDeepSearchGoalResult.Produced>(
            engine.execute(
                goal = searchGoal(),
                sourcePhoton = source,
                goalPhotonId = goalPhotonId,
                photons = listOf(photon("different-evidence", "changed evidence that must not be searched")),
                createdAt = at,
                resume = checkpoint,
                checkpointSink = DeepSearchCheckpointSink {
                    error("terminal recovery must not mutate final checkpoint")
                },
                missionId = mission.id,
            )
        )

        assertEquals(originalResult, produced.result)
        assertEquals(listOf(evidenceId), produced.evidencePhotonIds)
        assertTrue("deepsearch-mission:${mission.id.value}" in produced.photon.tags)
        assertTrue(produced.photon.id.value.startsWith("deep-search-result_"))
    }

    @Test
    fun `private no-export photons never become local DeepSearch evidence`() = runTest {
        val privatePhoton = Photon(
            id = PhotonId("private-corpus-evidence"),
            content = "lifeos photons private owner archive",
            confidence = 1.0,
            semanticMass = 4.0,
            provenance = Provenance("private-test", "owner", at.minusSeconds(2)),
            tags = setOf(
                "memory",
                "privacy:no-deepsearch-export",
                "privacy:no-external-export",
            ),
        )
        val publicPhoton = photon(
            id = "public-search-evidence",
            content = "lifeos photons public evidence",
        )
        val source = photon("search-source", "suche lifeos photons")
        val goalId = PhotonId("search-goal-private-boundary")
        val engine = LocalDeepSearchGoalEngine(sharedBudgets = null)

        val produced = assertIs<LocalDeepSearchGoalResult.Produced>(
            engine.execute(
                goal = searchGoal(),
                sourcePhoton = source,
                goalPhotonId = goalId,
                photons = listOf(privatePhoton, publicPhoton),
                createdAt = at,
            )
        )

        assertTrue(publicPhoton.id in produced.evidencePhotonIds)
        assertTrue(privatePhoton.id !in produced.evidencePhotonIds)
        assertTrue(privatePhoton.id !in produced.photon.provenance.parentIds)
    }

    private fun searchGoal() = GoalFrame(
        intent = IntentType.SEARCH,
        objective = "search: lifeos photons",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.95,
        language = LanguageCode.DE,
    )

    private fun photon(id: String, content: String) = Photon(
        id = PhotonId(id),
        content = content,
        confidence = 0.95,
        provenance = Provenance("test", "user", at.minusSeconds(1)),
        tags = setOf("chat"),
    )

    private class SingleSource(
        private val evidenceId: PhotonId,
    ) : DeepSearchSource {
        override val descriptor = DeepSearchSourceDescriptor(
            sourceId = LocalDeepSearchGoalEngine.LOCAL_SOURCE_ID,
            kind = DeepSearchSourceKind.LOCAL,
            permissionState = DeepSearchPermissionState.NOT_REQUIRED,
            reliability = 1.0,
        )

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            if (branch.depth > 0) return emptyList()
            return listOf(
                DeepSearchFindingDraft(
                    statement = "LIFEOS uses photons for information.",
                    semanticTerms = setOf("lifeos", "photons"),
                    confidence = 0.98,
                    evidence = listOf(
                        DeepSearchEvidenceDraft(
                            statement = "LIFEOS photon evidence",
                            confidence = 0.98,
                            sourcePhotonId = evidenceId,
                        )
                    ),
                )
            )
        }
    }
}
