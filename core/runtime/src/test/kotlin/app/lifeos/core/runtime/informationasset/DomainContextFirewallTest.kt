package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.runtime.convergence.AuditedCrossDomainBridge
import app.lifeos.core.runtime.convergence.CrossDomainBridgeRule
import app.lifeos.core.runtime.convergence.toConvergenceBoundary
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DomainContextFirewallTest {
    private val at = Instant.parse("2026-09-15T14:00:00Z")

    @Test
    fun `standard policies isolate organization legal finance and science scopes`() {
        assertTrue(FieldContextScope.ORGANIZATION_CONTEXT in StandardDomainContextPolicies.ORGANIZATION.allowedDirectScopes)
        assertFalse(FieldContextScope.FINANCIAL_CONTEXT in StandardDomainContextPolicies.LEGAL.allowedDirectScopes)
        assertFalse(FieldContextScope.LEGAL_CONTEXT in StandardDomainContextPolicies.FINANCE.allowedDirectScopes)
        assertFalse(FieldContextScope.SCIENTIFIC_CONTEXT in StandardDomainContextPolicies.ORGANIZATION.allowedDirectScopes)
        assertTrue(StandardDomainContextPolicies.ALL.values.none { it.allowGlobalMemory })
    }

    @Test
    fun `legal policy accepts legal direct context and rejects finance direct context`() {
        val legal = StandardDomainContextPolicies.LEGAL
        val allowed = FieldContext(
            temporal = TemporalContext(at),
            domain = DomainContext(StandardInformationDomains.LEGAL),
            activeScopes = setOf(FieldContextScope.CURRENT_TASK, FieldContextScope.LEGAL_CONTEXT),
        )
        val forbidden = allowed.copy(
            activeScopes = setOf(FieldContextScope.CURRENT_TASK, FieldContextScope.FINANCIAL_CONTEXT),
        )

        assertTrue(legal.validate(allowed).isEmpty())
        assertTrue(DomainContextViolation.FORBIDDEN_ACTIVE_SCOPE in legal.validate(forbidden))
    }

    @Test
    fun `policy converts to existing convergence boundary without widening scopes`() {
        val policy = StandardDomainContextPolicies.ORGANIZATION
        val boundary = policy.toConvergenceBoundary()

        assertEquals(policy.domainId, boundary.domainId)
        assertEquals(policy.allowedDirectScopes, boundary.allowedContextScopes)
        assertFalse(FieldContextScope.GLOBAL_MEMORY in boundary.allowedContextScopes)
    }

    @Test
    fun `cross domain admission requires exact audited source revision`() {
        val sourceRevision = InformationAssetRevisionId("a".repeat(64))
        val targetDomain = StandardInformationDomains.LEGAL
        val sourceDomain = StandardInformationDomains.FINANCE
        val rule = CrossDomainBridgeRule(
            id = "finance-to-legal-obligation",
            sourceDomainId = sourceDomain,
            targetDomainId = targetDomain,
            sourceHypothesisId = StableFieldIds.hypothesis(sourceDomain, "liability"),
            targetHypothesisId = StableFieldIds.hypothesis(targetDomain, "obligation"),
            targetNodeId = StableFieldIds.node(targetDomain, "claim", "obligation"),
            relation = EvidenceRelationType.SUPPORTS,
            weight = 0.8,
            targetSemanticKey = "obligation",
        )
        val bridge = AuditedCrossDomainBridge(
            rule = rule,
            bridgePurpose = "Use verified financial liability evidence when assessing legal obligation",
            sourceAssetRevisionId = sourceRevision,
        )
        val gate = InformationAssetContextGate()

        val withoutBridge = gate.evaluate(
            InformationAssetContextAdmissionRequest(
                sourceDomainId = sourceDomain,
                targetDomainId = targetDomain,
                requestedScopes = setOf(FieldContextScope.CURRENT_TASK, FieldContextScope.LEGAL_CONTEXT),
                sourceAssetRevisionId = sourceRevision,
            )
        )
        assertFalse(withoutBridge.admitted)
        assertTrue(InformationAssetContextRejection.CROSS_DOMAIN_BRIDGE_REQUIRED in withoutBridge.rejections)

        val admitted = gate.evaluate(
            InformationAssetContextAdmissionRequest(
                sourceDomainId = sourceDomain,
                targetDomainId = targetDomain,
                requestedScopes = setOf(FieldContextScope.CURRENT_TASK, FieldContextScope.LEGAL_CONTEXT),
                sourceAssetRevisionId = sourceRevision,
                bridge = bridge,
            )
        )
        assertTrue(admitted.admitted)
        assertTrue(admitted.requiresAuthorityReevaluation)

        val wrongRevision = gate.evaluate(
            InformationAssetContextAdmissionRequest(
                sourceDomainId = sourceDomain,
                targetDomainId = targetDomain,
                requestedScopes = setOf(FieldContextScope.LEGAL_CONTEXT),
                sourceAssetRevisionId = InformationAssetRevisionId("b".repeat(64)),
                bridge = bridge,
            )
        )
        assertFalse(wrongRevision.admitted)
        assertTrue(InformationAssetContextRejection.BRIDGE_SOURCE_REVISION_MISMATCH in wrongRevision.rejections)
    }

    @Test
    fun `global memory is never silently injected into standard domains`() {
        val result = InformationAssetContextGate().evaluate(
            InformationAssetContextAdmissionRequest(
                sourceDomainId = StandardInformationDomains.LEGAL,
                targetDomainId = StandardInformationDomains.LEGAL,
                requestedScopes = setOf(FieldContextScope.GLOBAL_MEMORY),
            )
        )

        assertFalse(result.admitted)
        assertTrue(InformationAssetContextRejection.FORBIDDEN_TARGET_SCOPE in result.rejections)
        assertTrue(InformationAssetContextRejection.GLOBAL_MEMORY_NOT_DIRECTLY_ADMISSIBLE in result.rejections)
    }

    @Test
    fun `bridge audit fingerprint binds purpose and exact source revision`() {
        val source = StandardInformationDomains.ORGANIZATION
        val target = StandardInformationDomains.LEGAL
        val rule = CrossDomainBridgeRule(
            id = "organization-to-legal",
            sourceDomainId = source,
            targetDomainId = target,
            sourceHypothesisId = StableFieldIds.hypothesis(source, "decision"),
            targetHypothesisId = StableFieldIds.hypothesis(target, "duty"),
            targetNodeId = StableFieldIds.node(target, "claim", "duty"),
            relation = EvidenceRelationType.SUPPORTS,
            weight = 0.7,
            targetSemanticKey = "duty",
        )
        val first = AuditedCrossDomainBridge(
            rule,
            "Map organization decision to legal duty",
            InformationAssetRevisionId("c".repeat(64)),
        )
        val changedPurpose = AuditedCrossDomainBridge(
            rule,
            "Map organization decision to legal duty after review",
            InformationAssetRevisionId("c".repeat(64)),
        )
        val changedRevision = AuditedCrossDomainBridge(
            rule,
            "Map organization decision to legal duty",
            InformationAssetRevisionId("d".repeat(64)),
        )

        assertNotEquals(first.fingerprint, changedPurpose.fingerprint)
        assertNotEquals(first.fingerprint, changedRevision.fingerprint)
    }
}
