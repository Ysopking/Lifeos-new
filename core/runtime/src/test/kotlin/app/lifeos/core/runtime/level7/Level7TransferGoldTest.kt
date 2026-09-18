package app.lifeos.core.runtime.level7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Level7TransferGoldTest {
    @Test
    fun differentlyNamedDomainYTransfersStructureWithoutSemanticIdentity() {
        val source = StructuralSignature(
            domainId = "X",
            topologyFingerprint = "chain-3-node",
            relationFingerprint = "supports-then-outcome",
            dimensionFingerprint = "reliability-strategy-outcome",
        )
        val target = StructuralSignature(
            domainId = "Y",
            topologyFingerprint = "chain-3-node",
            relationFingerprint = "supports-then-outcome",
            dimensionFingerprint = "reliability-strategy-outcome",
        )

        val candidate = StructuralSimilarityEngine().candidate(
            source = source,
            target = target,
            validationFingerprint = "cross-domain-validation:" + source.fingerprint(),
        )

        assertEquals(1.0, candidate.structuralSimilarity)
        assertFalse(candidate.semanticIdentityEstablished)
        assertFalse(candidate.directTransferActivationAllowed)

        val proof = TransferProof(
            proofId = "transfer:" + candidate.id,
            sourceDomainId = source.domainId,
            targetDomainId = target.domainId,
            semanticIdentityAssumed = candidate.semanticIdentityEstablished,
            structuralTransferCandidateId = candidate.id,
            validationFingerprint = "cross-domain-validation:" + source.fingerprint(),
            adaptedStrategyId = "strategy-Y-adapted:" + candidate.id,
        )

        assertFalse(proof.semanticIdentityAssumed)
        assertTrue(proof.adaptedStrategyId.contains("strategy-Y-adapted"))
    }
}
