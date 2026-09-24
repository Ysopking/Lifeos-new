package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldFormulaSemanticExecutionAuthorityTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun directRequestedActionRemainsExecutable() {
        val result = engine.understand("Erstelle ein Bild.")

        val decision = SemanticExecutionGate.evaluate(result.goal)

        assertTrue(decision.allowed)
        assertEquals("semantic-action-ready", decision.reason)
    }

    @Test
    fun plannedLanguageCannotAuthorizeImmediateAction() {
        val result = engine.understand("Erstelle ein Bild.")
        val node = result.goal.semanticActionGraph.executableNodes.single()
        val realized = result.goal.languageRealization.propositions.single {
            it.nodeId == node.id
        }
        val blockedRealization = result.goal.languageRealization.copy(
            propositions = result.goal.languageRealization.propositions
                .map {
                    if (it.nodeId != node.id) it
                    else it.copy(
                        temporalStatus = LanguageTemporalStatus.FUTURE,
                        modalStatuses = it.modalStatuses +
                            LanguageModalStatus.PLANNED,
                    )
                }
                .sortedBy { it.nodeId.value },
        )

        val decision = SemanticExecutionGate.evaluate(
            result.goal.copy(languageRealization = blockedRealization)
        )

        assertFalse(decision.allowed)
        assertEquals(
            "semantic-action-worldformula-modal:planned",
            decision.reason,
        )
    }

    @Test
    fun unresolvedWorldEvidenceBlocksActionEvenWhenSyntaxIsExecutable() {
        val result = engine.understand("Erstelle ein Bild.")
        val lattice = result.goal.interpretationLattice.copy(
            converged = false,
            worldEvidenceFingerprint = "a".repeat(64),
            unresolvedDueToWorldState = true,
        )

        val decision = SemanticExecutionGate.evaluate(
            result.goal.copy(interpretationLattice = lattice)
        )

        assertFalse(decision.allowed)
        assertEquals("semantic-action-world-state-unresolved", decision.reason)
    }

    @Test
    fun ambiguousRevisionGroundingCannotAuthorizeReferencedAction() {
        val now = Instant.parse("2026-09-24T12:00:00Z")
        val ref = PhotonRevisionRef(PhotonId("image-source"), 4L)
        val result = engine.understand(
            "Mach dieses Bild heller.",
            LanguageContext(
                now = now,
                items = listOf(
                    LanguageContextItem(
                        photonId = ref.photonId,
                        kind = "image",
                        tags = setOf("image"),
                        createdAt = now.minusSeconds(10),
                        active = true,
                        contentTerms = setOf("bild"),
                        revisionRef = ref,
                    )
                ),
            ),
        )
        val node = result.goal.semanticActionGraph.executableNodes.single {
            it.frame.predicate == PredicateConcept.TRANSFORM_IMAGE
        }
        val selected = node.frame.roles.values
            .mapNotNull { it.referencePhoton }
            .single()
        val ambiguous = LanguageReferenceGroundingState(
            references = listOf(
                LanguageReferenceGrounding(
                    expression = ReferenceExpression(
                        kind = ReferenceKind.THIS,
                        rawText = "dieses Bild",
                        preferredKinds = setOf("image"),
                        confidence = 1.0,
                    ),
                    selectedPhotonId = selected.photonId,
                    selectedRevisionRef = selected,
                    status = LanguageReferenceGroundingStatus.AMBIGUOUS,
                    score = 0.70,
                    runnerUpScore = 0.69,
                    matchedContextFingerprint = null,
                )
            )
        )

        val decision = SemanticExecutionGate.evaluate(
            result.goal.copy(
                referenceGrounding = ambiguous,
                clarification = ClarificationPlan.none(),
            )
        )

        assertFalse(decision.allowed)
        assertEquals(
            "semantic-action-reference-not-exact-revision",
            decision.reason,
        )
    }

    @Test
    fun legacyGoalWithoutWorldFormulaMetadataKeepsExistingAdmissionBehavior() {
        val result = engine.understand("Erstelle ein Bild.")
        val legacy = result.goal.copy(
            languageRealization = LanguageRealizationState.empty(),
            propositionGraph = SemanticPropositionGraph.empty(),
            referenceGrounding = LanguageReferenceGroundingState.empty(),
            temporalModalReality = LanguageTemporalModalRealityState.empty(),
        )

        assertTrue(SemanticExecutionGate.evaluate(legacy).allowed)
    }
}
