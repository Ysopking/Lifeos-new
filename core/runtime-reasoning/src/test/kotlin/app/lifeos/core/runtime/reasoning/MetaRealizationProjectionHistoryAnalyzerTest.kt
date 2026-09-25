package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MetaRealizationProjectionHistoryAnalyzerTest {
    private val analyzer = MetaRealizationProjectionHistoryAnalyzer()
    private val profile = profile()

    @Test
    fun `repeated coarse state with divergent successors proves non closure from history`() {
        val history = chain("coarse:x", "coarse:a", "coarse:x", "coarse:b")

        val result = requireNotNull(
            analyzer.analyze(
                history,
                RealizationComponentKind.COGNITIVE_STATE,
                "projection:cognition",
            )
        )

        assertEquals(ProjectionClosureStatus.NOT_CLOSED, result.status)
        assertEquals(
            listOf("coarse:a", "coarse:b"),
            result.conflicts.single().successorProjectionFingerprints,
        )
    }

    @Test
    fun `one observed transition remains unresolved`() {
        val result = requireNotNull(
            analyzer.analyze(
                chain("coarse:x", "coarse:a"),
                RealizationComponentKind.COGNITIVE_STATE,
            )
        )

        assertEquals(ProjectionClosureStatus.UNRESOLVED, result.status)
    }

    @Test
    fun `single snapshot has no transition evidence yet`() {
        assertNull(
            analyzer.analyze(
                chain("coarse:x"),
                RealizationComponentKind.COGNITIVE_STATE,
            )
        )
    }

    @Test
    fun `broken predecessor chain is rejected`() {
        val first = snapshot(1L, "coarse:x", null)
        val broken = snapshot(2L, "coarse:a", "wrong-predecessor")

        assertFailsWith<IllegalArgumentException> {
            analyzer.analyze(
                listOf(first, broken),
                RealizationComponentKind.COGNITIVE_STATE,
            )
        }
    }

    @Test
    fun `history ordering is canonicalized before evaluation`() {
        val ordered = chain("coarse:x", "coarse:a", "coarse:x", "coarse:b")
        val reversed = ordered.reversed()

        assertEquals(
            analyzer.analyze(ordered, RealizationComponentKind.COGNITIVE_STATE),
            analyzer.analyze(reversed, RealizationComponentKind.COGNITIVE_STATE),
        )
    }

    private fun chain(
        vararg semantics: String,
    ): List<MetaRealizationShadowSnapshot> {
        var predecessor: String? = null
        return semantics.mapIndexed { index, semantic ->
            val snapshot = snapshot(
                revision = index + 1L,
                semantic = semantic,
                predecessorRevisionId = predecessor,
            )
            predecessor = snapshot.realization.revisionId
            snapshot
        }
    }

    private fun snapshot(
        revision: Long,
        semantic: String,
        predecessorRevisionId: String?,
    ): MetaRealizationShadowSnapshot {
        val transition = predecessorRevisionId?.let {
            StableFieldIds.fingerprint(
                "projection-history-transition/v1",
                it,
                semantic,
            )
        }
        val realization = CanonicalRealizationState.create(
            revision = revision,
            asOf = T0.plusSeconds(revision),
            predecessorRevisionId = predecessorRevisionId,
            transitionFingerprint = transition,
            components = listOf(
                RealizationComponentRef(
                    kind = RealizationComponentKind.COGNITIVE_STATE,
                    representationId = "cognition:r$revision",
                    semanticFingerprint = semantic,
                )
            ),
        )
        val cycle = MetaRealizationCycle.start(realization, profile)
        val sourceState = "self:$revision"
        val issues = "issues:$revision"
        return MetaRealizationShadowSnapshot(
            sourceSelfStateFingerprint = sourceState,
            sourceIssueFingerprint = issues,
            realization = realization,
            cycle = cycle,
            fingerprint = StableFieldIds.fingerprint(
                "meta-realization-shadow-snapshot/v1",
                sourceState,
                issues,
                realization.representationFingerprint,
                cycle.fingerprint,
            ),
        )
    }

    private fun profile(): RealizationTransferProfile =
        RealizationTransferProfile.create(
            version = "projection-history-test",
            requiredComponents = listOf(
                RealizationComponentKind.COGNITIVE_STATE,
            ),
            projectionRegistryFingerprint = "projection:test",
            stateContractFingerprint = "state:test",
            observableIds = listOf("cognition"),
            invariantIds = listOf("projection-not-state"),
            failureCriterionIds = listOf("projection-not-closed"),
            frozenAt = T0,
        )

    private companion object {
        val T0: Instant = Instant.parse("2026-09-25T17:00:00Z")
    }
}
