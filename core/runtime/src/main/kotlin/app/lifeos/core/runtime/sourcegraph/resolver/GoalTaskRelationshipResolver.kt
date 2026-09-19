package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

class GoalTaskRelationshipResolver : DirectedSourceRelationshipResolver {
    override val resolverId: String = "goal-task-relationship"
    override val resolverVersion: String = "m208/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution> = buildList {
        addAll(resolveDirection(left, right))
        addAll(resolveDirection(right, left))
    }.distinctBy { Triple(it.sourceRef, it.targetRef, it.type) }
        .sortedWith(
            compareBy<DirectedSourceRelationshipResolution> { it.sourceRef.photonId.value }
                .thenBy { it.targetRef.photonId.value }
                .thenBy { it.type.name }
        )

    private fun resolveDirection(
        owner: SourceResolutionInput,
        other: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution> {
        val otherGoal = goalIdentity(other)
        val otherTask = taskIdentity(other)
        val otherAny = otherTask ?: otherGoal
        return buildList {
            relation(owner, other, "task:goal-id", otherGoal, SourceRelationshipType.BELONGS_TO_GOAL, RelationshipEvidenceKind.GOAL_ID)?.let(::add)
            relation(owner, other, "task:advances-goal-id", otherGoal, SourceRelationshipType.ADVANCES, RelationshipEvidenceKind.GOAL_ID)?.let(::add)
            relation(owner, other, "task:implements-id", otherAny, SourceRelationshipType.IMPLEMENTS, RelationshipEvidenceKind.OPERATIONAL_LINK)?.let(::add)
            relation(owner, other, "task:blocks-id", otherTask, SourceRelationshipType.BLOCKS, RelationshipEvidenceKind.TASK_ID)?.let(::add)
            relation(owner, other, "task:depends-on-id", otherTask, SourceRelationshipType.DEPENDS_ON, RelationshipEvidenceKind.TASK_ID)?.let(::add)
            relation(owner, other, "task:resolves-id", otherAny, SourceRelationshipType.RESOLVES, RelationshipEvidenceKind.OPERATIONAL_LINK)?.let(::add)
            relation(owner, other, "task:next-action-for-id", otherAny, SourceRelationshipType.NEXT_ACTION_FOR, RelationshipEvidenceKind.OPERATIONAL_LINK)?.let(::add)
        }
    }

    private fun relation(
        owner: SourceResolutionInput,
        other: SourceResolutionInput,
        attribute: String,
        targetId: String?,
        type: SourceRelationshipType,
        kind: RelationshipEvidenceKind,
    ): DirectedSourceRelationshipResolution? {
        val explicit = owner.metadata.technical.attributes[attribute]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (targetId == null || explicit != targetId) return null
        if (
            owner.metadata.externalObject.provider.providerId !=
                other.metadata.externalObject.provider.providerId
        ) return null
        return DirectedSourceRelationshipResolution(
            sourceRef = owner.sourceRef,
            targetRef = other.sourceRef,
            type = type,
            evidence = listOf(
                SourceRelationshipEvidence.create(
                    family = RelationshipEvidenceFamily.OPERATIONAL,
                    kind = kind,
                    strength = EvidenceStrength.DETERMINISTIC,
                    polarity = EvidencePolarity.POSITIVE,
                    sourceRef = owner.sourceRef,
                    confidence = 1.0,
                    explanation = "explicit $attribute target identity",
                )
            ),
        )
    }

    private fun goalIdentity(input: SourceResolutionInput): String? =
        input.metadata.technical.attributes["goal:id"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun taskIdentity(input: SourceResolutionInput): String? =
        input.metadata.technical.attributes["task:id"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: input.metadata.externalObject.externalId
                .takeIf { input.metadata.objectKind == SourceObjectKind.TASK }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}
