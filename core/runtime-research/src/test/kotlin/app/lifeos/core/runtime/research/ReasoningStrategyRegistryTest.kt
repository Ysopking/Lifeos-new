package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReasoningStrategyRegistryTest {
    @Test
    fun built_in_registry_is_deterministic_and_canonical() {
        val first = ReasoningStrategyRegistry()
        val second = ReasoningStrategyRegistry(
            ReasoningStrategyRegistry.builtInDescriptors().reversed()
        )

        assertEquals(first.all(), second.all())
        assertEquals(first.fingerprint(), second.fingerprint())
        assertEquals(first.all().sortedBy { it.id.value }, first.all())
        assertFalse(first.selectionAuthority)
        assertFalse(first.executionAuthority)
    }

    @Test
    fun duplicate_strategy_identity_fails_closed() {
        val descriptor = ReasoningStrategyRegistry.builtInDescriptors().first()

        assertFailsWith<IllegalArgumentException> {
            ReasoningStrategyRegistry(listOf(descriptor, descriptor))
        }
    }

    @Test
    fun descriptor_identity_binds_version_and_compatibility_metadata() {
        val base = ReasoningStrategyDescriptor.create(
            id = "test-method",
            version = "v1",
            kind = ReasoningStrategyKind.STRUCTURAL_HYPOTHESIS_SEARCH,
            executionClass = ReasoningStrategyExecutionClass.PURE_REASONING,
            applicableGapKinds = listOf(KnowledgeGapKind.EXPLICIT_UNKNOWN),
        )
        val changedVersion = ReasoningStrategyDescriptor.create(
            id = "test-method",
            version = "v2",
            kind = ReasoningStrategyKind.STRUCTURAL_HYPOTHESIS_SEARCH,
            executionClass = ReasoningStrategyExecutionClass.PURE_REASONING,
            applicableGapKinds = listOf(KnowledgeGapKind.EXPLICIT_UNKNOWN),
        )

        assertNotEquals(base.fingerprint, changedVersion.fingerprint)
    }

    @Test
    fun compatibility_never_widens_required_evidence_kind() {
        val gap = gap(
            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
            evidence = listOf(EvidenceActionKind.ASK_USER),
        )

        val compatible = ReasoningStrategyRegistry().compatibleWith(gap)

        assertTrue(compatible.any { it.kind == ReasoningStrategyKind.ACTIVE_EVIDENCE_SELECTION })
        assertFalse(compatible.any { it.kind == ReasoningStrategyKind.RECURSIVE_RESEARCH })
    }

    @Test
    fun recursive_research_is_compatible_only_when_deep_search_was_recommended() {
        val gap = gap(
            kind = KnowledgeGapKind.EXPLICIT_UNKNOWN,
            evidence = listOf(EvidenceActionKind.DEEP_SEARCH, EvidenceActionKind.ASK_USER),
        )

        val compatible = ReasoningStrategyRegistry().compatibleWith(gap)

        assertTrue(compatible.any { it.kind == ReasoningStrategyKind.RECURSIVE_RESEARCH })
        assertTrue(compatible.all { !it.executionAuthority && !it.promotionAuthority })
    }

    @Test
    fun abstention_requires_explicit_b380_abstain_recommendation() {
        val without = gap(
            kind = KnowledgeGapKind.SEARCH_TRUNCATED,
            evidence = listOf(EvidenceActionKind.SIMULATION),
        )
        val with = gap(
            kind = KnowledgeGapKind.SEARCH_TRUNCATED,
            evidence = listOf(EvidenceActionKind.SIMULATION, EvidenceActionKind.ABSTAIN),
        )
        val registry = ReasoningStrategyRegistry()

        assertFalse(registry.compatibleWith(without).any {
            it.kind == ReasoningStrategyKind.EXPLICIT_ABSTENTION
        })
        assertTrue(registry.compatibleWith(with).any {
            it.kind == ReasoningStrategyKind.EXPLICIT_ABSTENTION
        })
    }

    private fun gap(
        kind: KnowledgeGapKind,
        evidence: List<EvidenceActionKind>,
    ): KnowledgeGap = KnowledgeGap.create(
        kind = kind,
        sourceCycleId = "cycle",
        semanticKey = "key:$kind",
        rationale = "test",
        sourceFingerprint = "source:$kind",
        relatedRefs = listOf("ref:$kind"),
        severity = 0.8,
        recommendedEvidenceKinds = evidence,
    )
}
