package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.thought.ThoughtMatrixSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaInferenceEngineTest {
    private val empty = ThoughtMatrixSnapshot.empty(
        Instant.parse("2026-09-26T12:00:00Z")
    )

    @Test
    fun directObservationSeparationShortCircuitsStructuralTraversal() {
        val result = MetaInferenceEngine().infer(
            group = group(
                candidate("a", "audit-a", "comparison-a"),
                candidate("b", "audit-b", "comparison-b"),
            ),
            snapshot = empty,
        )

        assertEquals(MetaInferenceStatus.DISTINCT, result.status)
        assertTrue(result.structuralSignatures.isEmpty())
        assertTrue(result.identifiability?.fullyIdentifiable == true)
        assertFalse(result.mergeAuthority)
    }

    @Test
    fun unresolvedCandidatesRemainInformationRequiredNotIdentical() {
        val result = MetaInferenceEngine().infer(
            group = group(
                candidate("a", "audit-a", "same"),
                candidate("b", "audit-b", "same"),
            ),
            snapshot = empty,
            budget = MetaInferenceBudget(
                structural = StructuralAnalysisBudget(maxDepth = 2),
            ),
        )

        assertEquals(MetaInferenceStatus.INFORMATION_REQUIRED, result.status)
        assertTrue(result.informationRequired)
        assertTrue(result.identifiability?.unresolvedPairs?.isNotEmpty() == true)
        assertFalse(result.truthAuthority)
        assertFalse(result.mergeAuthority)
    }

    private fun candidate(
        id: String,
        audit: String,
        comparison: String,
    ) = MetaCandidateRef(
        photonId = PhotonId(id),
        sourceRevision = 1,
        domain = MetaDomainFamily.DOCUMENT,
        observationFingerprint = audit,
        comparisonFingerprint = comparison,
        stateFingerprint = "state-$id",
    )

    private fun group(vararg refs: MetaCandidateRef): MetaCandidateGroup {
        val canonical = refs.toList().sortedBy { it.photonId.value }
        return MetaCandidateGroup(
            equivalenceCandidateFingerprint = "equivalence",
            candidates = canonical,
            fingerprint = StableFieldIds.fingerprint(
                "meta-candidate-group/v1",
                "equivalence",
                *canonical.map { it.fingerprint }.toTypedArray(),
            ),
        )
    }
}
