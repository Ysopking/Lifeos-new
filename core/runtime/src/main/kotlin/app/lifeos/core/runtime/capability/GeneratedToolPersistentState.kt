package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds

/**
 * Persisted receipt for one already accepted J03 promotion. It preserves every content-addressed
 * J03 field but cannot be passed to the live promotion primitive, so rehydration never manufactures
 * fresh promotion authority from disk.
 */
data class GeneratedToolPromotionReceipt(
    val evidenceId: String,
    val toolId: String,
    val candidateArtifactId: String,
    val candidateId: String,
    val verificationId: String,
    val provenanceId: String,
    val recordFingerprint: String,
    val trialEvidenceId: String,
    val promotionPolicyFingerprint: String,
    val apkSha256: String,
    val capabilityChangeFingerprint: String,
    val permissionDeltaFingerprint: String,
    val reviewerEvidenceFingerprints: List<String>,
    val promotionActorEvidenceFingerprints: List<String>,
) {
    init {
        require(evidenceId.isNotBlank())
        require(toolId.isNotBlank())
        require(candidateArtifactId.isNotBlank())
        require(candidateId.isNotBlank() && verificationId.isNotBlank() && provenanceId.isNotBlank())
        require(recordFingerprint.isNotBlank() && trialEvidenceId.isNotBlank())
        require(promotionPolicyFingerprint.isNotBlank())
        require(apkSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(capabilityChangeFingerprint.isNotBlank())
        require(permissionDeltaFingerprint.isNotBlank())
        require(reviewerEvidenceFingerprints.isNotEmpty())
        require(promotionActorEvidenceFingerprints.isNotEmpty())
        require(reviewerEvidenceFingerprints.none { it.isBlank() })
        require(promotionActorEvidenceFingerprints.none { it.isBlank() })
        require(evidenceId == computedEvidenceId()) {
            "Persisted promotion receipt does not reproduce its J03 evidence id"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-promotion-receipt/v1",
        evidenceId,
        toolId,
        candidateArtifactId,
        candidateId,
        verificationId,
        provenanceId,
        recordFingerprint,
        trialEvidenceId,
        promotionPolicyFingerprint,
        apkSha256.lowercase(),
        capabilityChangeFingerprint,
        permissionDeltaFingerprint,
        *reviewerEvidenceFingerprints.sorted().map { "reviewer:$it" }.toTypedArray(),
        *promotionActorEvidenceFingerprints.sorted().map { "promotion:$it" }.toTypedArray(),
    )

    /** A persisted receipt is evidence only and is never accepted by the live promotion method. */
    val activationAllowed: Boolean = false

    private fun computedEvidenceId(): String = StableFieldIds.fingerprint(
        "generated-tool-promotion-evidence/v1",
        toolId,
        candidateArtifactId,
        candidateId,
        verificationId,
        provenanceId,
        recordFingerprint,
        trialEvidenceId,
        promotionPolicyFingerprint,
        apkSha256.lowercase(),
        capabilityChangeFingerprint,
        permissionDeltaFingerprint,
        *reviewerEvidenceFingerprints.sorted().map { "reviewer:$it" }.toTypedArray(),
        *promotionActorEvidenceFingerprints.sorted().map { "promotion:$it" }.toTypedArray(),
    )

    companion object {
        fun from(evidence: GeneratedToolPromotionEvidence): GeneratedToolPromotionReceipt =
            GeneratedToolPromotionReceipt(
                evidenceId = evidence.id,
                toolId = evidence.toolId,
                candidateArtifactId = evidence.candidateArtifactId,
                candidateId = evidence.candidateId,
                verificationId = evidence.verificationId,
                provenanceId = evidence.provenanceId,
                recordFingerprint = evidence.recordFingerprint,
                trialEvidenceId = evidence.trialEvidenceId,
                promotionPolicyFingerprint = evidence.promotionPolicyFingerprint,
                apkSha256 = evidence.apkSha256,
                capabilityChangeFingerprint = evidence.capabilityChangeFingerprint,
                permissionDeltaFingerprint = evidence.permissionDeltaFingerprint,
                reviewerEvidenceFingerprints = evidence.reviewerEvidenceFingerprints,
                promotionActorEvidenceFingerprints = evidence.promotionActorEvidenceFingerprints,
            )
    }
}

/**
 * J10 durable lifecycle state. Record, audit chain, trial evidence and accepted J03 promotion
 * receipt are restored as one verified unit; none of these fields grants activation authority.
 */
data class GeneratedToolPersistentState(
    val record: GeneratedToolRecord,
    val auditEntries: List<GeneratedToolAuditEntry>,
    val trialEvidence: GeneratedToolTrialEvidence,
    val promotionReceipt: GeneratedToolPromotionReceipt? = null,
) {
    init {
        val toolId = record.manifest.toolId
        require(trialEvidence.toolId == toolId) {
            "Persisted trial evidence belongs to another generated tool"
        }
        GeneratedToolStateIntegrity.requireValidAudit(record, auditEntries)

        val promotionId = record.promotionEvidenceId
        if (promotionId == null) {
            require(promotionReceipt == null) {
                "Persisted promotion receipt requires a record promotion evidence id"
            }
        } else {
            val receipt = requireNotNull(promotionReceipt) {
                "Persisted promoted lifecycle state requires the full J03 promotion receipt"
            }
            require(receipt.evidenceId == promotionId) {
                "Persisted promotion receipt id does not match generated tool record"
            }
            require(receipt.toolId == toolId) {
                "Persisted promotion receipt belongs to another generated tool"
            }
            require(receipt.trialEvidenceId == trialEvidence.id) {
                "Persisted promotion receipt does not match exact trial evidence"
            }
            val originalTrialRecord = record.copy(
                state = GeneratedToolState.TRIAL,
                promotionEvidenceId = null,
            )
            require(receipt.recordFingerprint == originalTrialRecord.promotionRecordFingerprint()) {
                "Persisted promotion receipt does not bind the original TRIAL record"
            }
            val lastPromotion = auditEntries.lastOrNull { it.action == GeneratedToolAuditAction.PROMOTED }
            require(lastPromotion != null) {
                "Persisted promotion receipt requires a PROMOTED audit entry"
            }
            require(lastPromotion.reason == "j03-promotion-evidence:${receipt.evidenceId}") {
                "Persisted promotion receipt does not match the last promotion audit"
            }
        }

        if (record.state == GeneratedToolState.ACTIVE) {
            require(promotionReceipt != null) { "ACTIVE restore requires a full promotion receipt" }
            require(auditEntries.last().action == GeneratedToolAuditAction.PROMOTED) {
                "ACTIVE restore must end at the promotion audit entry"
            }
            require(trialEvidence.stats.safetyViolations == 0) {
                "ACTIVE restore cannot contain trial safety violations"
            }
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-persistent-state/v1",
        record.auditFingerprint(),
        auditEntries.last().id,
        trialEvidence.id,
        promotionReceipt?.id.orEmpty(),
    )
}

/**
 * Persistence boundary shared by GeneratedToolRegistry and GeneratedToolTrialLedger. Implementations
 * must update each operation atomically and preserve the other members of the same tool bucket.
 */
interface GeneratedToolStateRepository {
    suspend fun loadAll(): List<GeneratedToolPersistentState>

    suspend fun persistLifecycle(
        record: GeneratedToolRecord,
        auditEntries: List<GeneratedToolAuditEntry>,
        promotionEvidence: GeneratedToolPromotionEvidence? = null,
    )

    suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence)
}

data class GeneratedToolRehydrationReport(
    val restoredTools: Int,
    val restoredTrialResults: Int,
    val restoredActiveProviders: Int,
) {
    init {
        require(restoredTools >= 0)
        require(restoredTrialResults >= 0)
        require(restoredActiveProviders >= 0)
        require(restoredActiveProviders <= restoredTools)
    }
}

/**
 * Restores an empty in-process generated-tool runtime from durable, already integrity-checked state.
 * ACTIVE providers are re-registered only with an exact non-activating J03 receipt and the same
 * promotion policy; all validation is completed before the first in-memory mutation.
 */
class GeneratedToolStateRehydrator(
    private val repository: GeneratedToolStateRepository,
    private val tools: GeneratedToolRegistry,
    private val trialLedger: GeneratedToolTrialLedger,
    private val capabilityRegistry: CapabilityRegistry? = null,
    private val promotionPolicy: GeneratedToolPromotionPolicy = GeneratedToolPromotionPolicy(),
) {
    suspend fun rehydrate(): GeneratedToolRehydrationReport {
        require(tools.snapshot().isEmpty()) {
            "Generated-tool rehydration requires an empty registry"
        }
        require(trialLedger.isEmpty()) {
            "Generated-tool rehydration requires an empty trial ledger"
        }
        val existingGeneratedProviders = capabilityRegistry
            ?.all(includeUnavailable = true)
            ?.filter { it.providerType == ProviderType.GENERATED_TOOL }
            .orEmpty()
        require(existingGeneratedProviders.isEmpty()) {
            "Generated-tool rehydration requires no pre-registered generated providers"
        }

        val states = repository.loadAll().sortedBy { it.record.manifest.toolId }
        require(states.map { it.record.manifest.toolId }.distinct().size == states.size) {
            "Durable generated-tool state contains duplicate tool identities"
        }

        // Preflight every state before mutating any in-process registry.
        states.forEach { state ->
            GeneratedToolStateIntegrity.requireValidAudit(state.record, state.auditEntries)
            state.promotionReceipt?.let { require(!it.activationAllowed) }
            if (state.record.state == GeneratedToolState.ACTIVE) {
                val receipt = requireNotNull(state.promotionReceipt)
                require(receipt.promotionPolicyFingerprint == promotionPolicy.fingerprint()) {
                    "ACTIVE persisted promotion receipt uses another promotion policy"
                }
                requirePromotionEligible(state.record, state.trialEvidence.stats)
            }
        }

        states.forEach { state ->
            tools.restore(state)
            trialLedger.restore(state.trialEvidence)
        }

        var activeProviders = 0
        states.filter { it.record.state == GeneratedToolState.ACTIVE }.forEach { state ->
            val receipt = requireNotNull(state.promotionReceipt)
            capabilityRegistry?.registerGeneratedRestored(
                descriptor = state.toCapabilityDescriptor(),
                activeRecord = state.record,
                receipt = receipt,
            )
            if (capabilityRegistry != null) activeProviders += 1
        }

        return GeneratedToolRehydrationReport(
            restoredTools = states.size,
            restoredTrialResults = states.sumOf { it.trialEvidence.stats.trials },
            restoredActiveProviders = activeProviders,
        )
    }

    private fun requirePromotionEligible(record: GeneratedToolRecord, stats: GeneratedToolTrialStats) {
        require(stats.trials >= promotionPolicy.minimumTrials) {
            "Persisted promotion receipt no longer satisfies minimum trials"
        }
        require(stats.successRate >= promotionPolicy.minimumSuccessRate) {
            "Persisted promotion receipt no longer satisfies success rate"
        }
        require(stats.expectedOutputRate >= promotionPolicy.minimumExpectedOutputRate) {
            "Persisted promotion receipt no longer satisfies expected-output rate"
        }
        require(stats.safetyViolations == 0) {
            "Persisted promotion receipt contains safety violations"
        }
        require(record.verificationConfidence >= promotionPolicy.minimumVerificationConfidence) {
            "Persisted promotion receipt no longer satisfies verification confidence"
        }
    }

    private fun GeneratedToolPersistentState.toCapabilityDescriptor() = CapabilityDescriptor(
        capabilityId = record.manifest.sourceCapability,
        providerId = record.manifest.toolId,
        providerType = ProviderType.GENERATED_TOOL,
        contract = CapabilityContract(record.manifest.requiredInputs, record.manifest.requiredOutputs),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.LOW,
        reliability = trialEvidence.stats.successRate,
        cost = 0.0,
    )
}

internal object GeneratedToolStateIntegrity {
    fun requireValidAudit(record: GeneratedToolRecord, entries: List<GeneratedToolAuditEntry>) {
        require(entries.isNotEmpty()) { "Persisted generated-tool audit chain cannot be empty" }
        val toolId = record.manifest.toolId
        require(entries.all { it.toolId == toolId }) {
            "Persisted generated-tool audit chain mixes tool identities"
        }

        entries.forEachIndexed { index, entry ->
            if (index == 0) {
                require(entry.action == GeneratedToolAuditAction.REGISTERED)
                require(entry.fromState == null)
                require(entry.beforeRecordFingerprint == null)
                require(entry.previousEntryId == null)
                require(entry.toState != GeneratedToolState.ACTIVE) {
                    "Generated tool cannot be restored as directly registered ACTIVE"
                }
            } else {
                val previous = entries[index - 1]
                require(entry.previousEntryId == previous.id) {
                    "Persisted generated-tool audit previous-entry link is broken"
                }
                require(entry.fromState == previous.toState) {
                    "Persisted generated-tool audit state continuity is broken"
                }
                require(entry.beforeRecordFingerprint == previous.afterRecordFingerprint) {
                    "Persisted generated-tool audit record continuity is broken"
                }
                requireValidMutation(entry)
            }
        }

        require(entries.last().toState == record.state) {
            "Persisted generated-tool audit final state does not match record"
        }
        require(entries.last().afterRecordFingerprint == record.auditFingerprint()) {
            "Persisted generated-tool audit final fingerprint does not match record"
        }
    }

    private fun requireValidMutation(entry: GeneratedToolAuditEntry) {
        val from = requireNotNull(entry.fromState)
        when (entry.action) {
            GeneratedToolAuditAction.REGISTERED -> error("Registration may only be the first audit entry")
            GeneratedToolAuditAction.PROMOTED -> {
                require(from == GeneratedToolState.TRIAL && entry.toState == GeneratedToolState.ACTIVE) {
                    "PROMOTED audit must be TRIAL -> ACTIVE"
                }
            }
            GeneratedToolAuditAction.ROLLED_BACK -> {
                require(from == GeneratedToolState.ACTIVE && entry.toState == GeneratedToolState.QUARANTINED) {
                    "ROLLED_BACK audit must be ACTIVE -> QUARANTINED"
                }
            }
            else -> {
                val allowed = NORMAL_TRANSITIONS.getValue(from)
                require(entry.toState in allowed) {
                    "Persisted generated-tool audit contains invalid lifecycle transition"
                }
                require(entry.action == expectedNormalAction(entry.toState)) {
                    "Persisted generated-tool audit action does not match lifecycle state"
                }
            }
        }
    }

    private fun expectedNormalAction(to: GeneratedToolState): GeneratedToolAuditAction = when (to) {
        GeneratedToolState.REJECTED -> GeneratedToolAuditAction.REJECTED
        GeneratedToolState.QUARANTINED -> GeneratedToolAuditAction.QUARANTINED
        GeneratedToolState.RETIRED -> GeneratedToolAuditAction.RETIRED
        else -> GeneratedToolAuditAction.TRANSITIONED
    }

    private val NORMAL_TRANSITIONS: Map<GeneratedToolState, Set<GeneratedToolState>> = mapOf(
        GeneratedToolState.GENERATED to setOf(GeneratedToolState.BUILT, GeneratedToolState.REJECTED),
        GeneratedToolState.BUILT to setOf(GeneratedToolState.TESTED, GeneratedToolState.REJECTED),
        GeneratedToolState.TESTED to setOf(GeneratedToolState.VERIFIED, GeneratedToolState.REJECTED),
        GeneratedToolState.VERIFIED to setOf(GeneratedToolState.TRIAL, GeneratedToolState.REJECTED),
        GeneratedToolState.TRIAL to setOf(GeneratedToolState.QUARANTINED, GeneratedToolState.REJECTED),
        GeneratedToolState.ACTIVE to emptySet(),
        GeneratedToolState.QUARANTINED to setOf(GeneratedToolState.TRIAL, GeneratedToolState.RETIRED),
        GeneratedToolState.REJECTED to setOf(GeneratedToolState.RETIRED),
        GeneratedToolState.RETIRED to emptySet(),
    )
}
