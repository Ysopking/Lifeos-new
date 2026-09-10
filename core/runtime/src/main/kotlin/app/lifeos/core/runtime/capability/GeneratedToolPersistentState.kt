package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds

/**
 * J10 durable lifecycle state. Record, audit chain, trial evidence and accepted J03 promotion
 * evidence are restored as one verified unit; none of these fields grants activation authority.
 */
data class GeneratedToolPersistentState(
    val record: GeneratedToolRecord,
    val auditEntries: List<GeneratedToolAuditEntry>,
    val trialEvidence: GeneratedToolTrialEvidence,
    val promotionEvidence: GeneratedToolPromotionEvidence? = null,
) {
    init {
        val toolId = record.manifest.toolId
        require(trialEvidence.toolId == toolId) {
            "Persisted trial evidence belongs to another generated tool"
        }
        GeneratedToolStateIntegrity.requireValidAudit(record, auditEntries)

        val promotionId = record.promotionEvidenceId
        if (promotionId == null) {
            require(promotionEvidence == null) {
                "Persisted promotion evidence requires a record promotion evidence id"
            }
        } else {
            val evidence = requireNotNull(promotionEvidence) {
                "Persisted promoted lifecycle state requires full J03 promotion evidence"
            }
            require(evidence.id == promotionId) {
                "Persisted promotion evidence id does not match generated tool record"
            }
            require(evidence.toolId == toolId) {
                "Persisted promotion evidence belongs to another generated tool"
            }
            require(evidence.trialEvidenceId == trialEvidence.id) {
                "Persisted promotion evidence does not match exact trial evidence"
            }
            val originalTrialRecord = record.copy(
                state = GeneratedToolState.TRIAL,
                promotionEvidenceId = null,
            )
            require(evidence.recordFingerprint == originalTrialRecord.promotionRecordFingerprint()) {
                "Persisted promotion evidence does not bind the original TRIAL record"
            }
            val lastPromotion = auditEntries.lastOrNull { it.action == GeneratedToolAuditAction.PROMOTED }
            require(lastPromotion != null) {
                "Persisted promotion evidence requires a PROMOTED audit entry"
            }
            require(lastPromotion.reason == "j03-promotion-evidence:${evidence.id}") {
                "Persisted promotion evidence does not match the last promotion audit"
            }
        }

        if (record.state == GeneratedToolState.ACTIVE) {
            require(promotionEvidence != null) { "ACTIVE restore requires full promotion evidence" }
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
        promotionEvidence?.id.orEmpty(),
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
 * ACTIVE providers are re-registered only with exact persisted J03 evidence and the same promotion
 * policy; all validation is completed before the first in-memory mutation.
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
            val evidence = state.promotionEvidence
            if (evidence != null) {
                require(evidence.promotionPolicyFingerprint == promotionPolicy.fingerprint()) {
                    "Persisted promotion evidence uses another promotion policy"
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
            val evidence = requireNotNull(state.promotionEvidence)
            capabilityRegistry?.registerGenerated(
                descriptor = state.toCapabilityDescriptor(),
                activeRecord = state.record,
                evidence = evidence,
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
            "Persisted promotion evidence no longer satisfies minimum trials"
        }
        require(stats.successRate >= promotionPolicy.minimumSuccessRate) {
            "Persisted promotion evidence no longer satisfies success rate"
        }
        require(stats.expectedOutputRate >= promotionPolicy.minimumExpectedOutputRate) {
            "Persisted promotion evidence no longer satisfies expected-output rate"
        }
        require(stats.safetyViolations == 0) {
            "Persisted promotion evidence contains safety violations"
        }
        require(record.verificationConfidence >= promotionPolicy.minimumVerificationConfidence) {
            "Persisted promotion evidence no longer satisfies verification confidence"
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
