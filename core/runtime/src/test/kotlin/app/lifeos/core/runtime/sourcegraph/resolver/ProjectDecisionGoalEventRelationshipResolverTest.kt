package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProjectHint
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.model.source.SourceTechnicalMetadata
import app.lifeos.core.model.source.SourceTimestamps
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipPolicy
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipState
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectDecisionGoalEventRelationshipResolverTest {
    private val policy = SourceRelationshipPolicy()
    private val at = Instant.parse("2026-09-19T16:00:00Z")

    @Test
    fun explicitProjectIdConfirmsWhileNameAloneStaysCandidate() {
        val left = input("left", SourceObjectKind.OTHER, project = SourceProjectHint(explicitProjectId = "p-1", projectName = "LifeOS"))
        val right = input("right", SourceObjectKind.PROJECT, project = SourceProjectHint(explicitProjectId = "p-1", projectName = "LifeOS"))
        val confirmed = ProjectRelationshipResolver().resolve(left, right)
            .single { it.type == SourceRelationshipType.SAME_PROJECT }
        assertEquals(SourceRelationshipState.CONFIRMED, policy.evaluate(confirmed.type, confirmed.evidence).state)
        assertTrue(ProjectRelationshipResolver().resolve(left, right).any { it.type == SourceRelationshipType.BELONGS_TO_PROJECT })

        val nameOnlyLeft = input("name-a", SourceObjectKind.OTHER, project = SourceProjectHint(projectName = "LifeOS"))
        val nameOnlyRight = input("name-b", SourceObjectKind.OTHER, project = SourceProjectHint(projectName = "LifeOS"))
        val candidate = ProjectRelationshipResolver().resolve(nameOnlyLeft, nameOnlyRight)
            .single { it.type == SourceRelationshipType.SAME_PROJECT }
        assertEquals(SourceRelationshipState.CANDIDATE, policy.evaluate(candidate.type, candidate.evidence).state)
    }

    @Test
    fun repositoryIssueAndNameCanBecomeMergeEligibleWithoutPretendingDeterminism() {
        val hint = SourceProjectHint(projectName = "LifeOS", repository = "Ysopking/Lifeos-new", issue = "42")
        val left = input("a", SourceObjectKind.OTHER, project = hint)
        val right = input("b", SourceObjectKind.CONVERSATION, project = hint)
        val same = ProjectRelationshipResolver().resolve(left, right)
            .single { it.type == SourceRelationshipType.SAME_PROJECT }
        assertEquals(SourceRelationshipState.MERGE_ELIGIBLE, policy.evaluate(same.type, same.evidence).state)
    }

    @Test
    fun decisionRolesStayDistinct() {
        val decision = input("decision", SourceObjectKind.OTHER, attributes = mapOf("decision:id" to "d-7"))
        val actor = input(
            "actor",
            SourceObjectKind.MESSAGE,
            attributes = mapOf(
                "decision:proposes-id" to "d-7",
                "decision:approves-id" to "d-7",
            ),
        )
        val types = DecisionRelationshipResolver().resolve(actor, decision).map { it.type }.toSet()
        assertTrue(SourceRelationshipType.PROPOSES in types)
        assertTrue(SourceRelationshipType.APPROVES in types)
        assertFalse(SourceRelationshipType.DECIDES in types)
    }

    @Test
    fun taskGoalDirectionsArePreserved() {
        val goal = input("goal", SourceObjectKind.OTHER, attributes = mapOf("goal:id" to "g-1"))
        val task = input(
            "task",
            SourceObjectKind.TASK,
            attributes = mapOf(
                "task:id" to "t-1",
                "task:goal-id" to "g-1",
                "task:advances-goal-id" to "g-1",
            ),
        )
        val types = GoalTaskRelationshipResolver().resolve(task, goal).map { it.type }.toSet()
        assertTrue(SourceRelationshipType.BELONGS_TO_GOAL in types)
        assertTrue(SourceRelationshipType.ADVANCES in types)
    }

    @Test
    fun calendarUidConfirmsSameEventButTitleAndTimeAloneDoNot() {
        val first = input(
            "event-a",
            SourceObjectKind.CALENDAR_EVENT,
            attributes = mapOf("event:uid" to "uid-1", "event:title" to "Daily"),
        )
        val second = input(
            "event-b",
            SourceObjectKind.CALENDAR_EVENT,
            attributes = mapOf("event:uid" to "uid-1", "event:title" to "Daily"),
        )
        val exact = EventRelationshipResolver().resolve(first, second)
            .single { it.type == SourceRelationshipType.SAME_EVENT }
        assertEquals(SourceRelationshipState.CONFIRMED, policy.evaluate(exact.type, exact.evidence).state)

        val weakA = input("weak-a", SourceObjectKind.CALENDAR_EVENT, attributes = mapOf("event:title" to "Daily"))
        val weakB = input("weak-b", SourceObjectKind.CALENDAR_EVENT, attributes = mapOf("event:title" to "Daily"))
        val weak = EventRelationshipResolver().resolve(weakA, weakB)
            .single { it.type == SourceRelationshipType.SAME_EVENT }
        assertEquals(SourceRelationshipState.CANDIDATE, policy.evaluate(weak.type, weak.evidence).state)
    }

    private fun input(
        id: String,
        kind: SourceObjectKind,
        project: SourceProjectHint? = null,
        attributes: Map<String, String> = emptyMap(),
    ): SourceResolutionInput = SourceResolutionInput(
        sourceRef = PhotonRevisionRef(PhotonId(id), 1),
        metadata = CanonicalSourceMetadata(
            objectKind = kind,
            origin = SourceMetadataOrigin.CONNECTOR,
            privacyZone = SourcePrivacyZone.PRIVATE,
            externalObject = SourceExternalObjectRef(
                provider = SourceProviderRef("test"),
                account = SourceAccountRef("test", "account"),
                objectKind = kind,
                externalId = id,
                externalVersion = "v1",
            ),
            timestamps = SourceTimestamps(occurredAt = at),
            projectHint = project,
            technical = SourceTechnicalMetadata(attributes = attributes),
        ),
    )
}
