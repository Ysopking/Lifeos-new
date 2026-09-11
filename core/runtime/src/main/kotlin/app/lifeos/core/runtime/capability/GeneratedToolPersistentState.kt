package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds

/** Persisted activation proof is evidence only and can never invoke a live promotion primitive. */
sealed interface GeneratedToolActivationReceipt {
    val id: String
    val evidenceId: String
    val toolId: String
    val recordFingerprint: String
    val trialEvidenceId: String
    val promotionPolicyFingerprint: String
    val activationAllowed: Boolean
}

/**
 * Persisted receipt for one already accepted J03 promotion. It preserves every content-addressed
 * J03 field but cannot be passed to the live promotion primitive, so rehydration never manufactures
 * fresh promotion authority from disk.
 */
data class GeneratedToolPromotionReceipt(
    override val evidenceId: String,
    override val toolId: String,
    val candidateArtifactId: String,
    val candidateId: String,
    val verificationId: String,
    val provenanceId: String,
    override val recordFingerprint: String,
    override val trialEvidenceId: String,
    override val promotionPolicyFingerprint: String,
    val apkSha256: String,
    val capabilityChangeFingerprint: String,
    val permissionDeltaFingerprint: String,
    val reviewerEvidenceFingerprints: List<String>,
    val promotionActorEvidenceFingerprints: List<String>,
) : GeneratedToolActivationReceipt {
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

    override val id: String = StableFieldIds.fingerprint(
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
    override val activationAllowed: Boolean = false

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
 * Durable receipt shape reserved for the bounded novel-capability promotion path. It binds only
 * immutable proof references and carries no activation authority. V1.2 can store and validate this
 * receipt, but runtime rehydration deliberately refuses to restore it as an ACTIVE provider until
 * the later bounded admission/promotion gates exist.
 */
data class BoundedGeneratedToolPromotionReceipt(
    override val evidenceId: String,
    override val toolId: String,
    val artifactId: String,
    override val recordFingerprint: String,
    override val trialEvidenceId: String,
    override val promotionPolicyFingerprint: String,
    val novelAdmissionEvidenceId: String,
    val canaryReadinessEvidenceId: String,
    val promotionSealId: String,
    val reviewerEvidenceFingerprints: List<String>,
    val activationActorEvidenceFingerprints: List<String>,
) : GeneratedToolActivationReceipt {
    init {
        require(evidenceId.isNotBlank())
        require(toolId.isNotBlank())
        require(artifactId.isNotBlank())
        require(recordFingerprint.isNotBlank() && trialEvidenceId.isNotBlank())
        require(promotionPolicyFingerprint.isNotBlank())
        require(novelAdmissionEvidenceId.isNotBlank())
        require(canaryReadinessEvidenceId.isNotBlank())
        require(promotionSealId.isNotBlank())
        require(reviewerEvidenceFingerprints.isNotEmpty())
        require(activationActorEvidenceFingerprints.isNotEmpty())
        require(reviewerEvidenceFingerprints.none { it.isBlank() })
        require(activationActorEvidenceFingerprints.none { it.isBlank() })
        require(
            evidenceId == boundedGeneratedToolPromotionEvidenceId(
                toolId = toolId,
                artifactId = artifactId,
                recordFingerprint = recordFingerprint,
                trialEvidenceId = trialEvidenceId,
                promotionPolicyFingerprint = promotionPolicyFingerprint,
                novelAdmissionEvidenceId = novelAdmissionEvidenceId,
                canaryReadinessEvidenceId = canaryReadinessEvidenceId,
                promotionSealId = promotionSealId,
                reviewerEvidenceFingerprints = reviewerEvidenceFingerprints,
                activationActorEvidenceFingerprints = activationActorEvidenceFingerprints,
            )
        ) { "Persisted bounded promotion receipt does not reproduce its evidence id" }
    }

    override val id: String = StableFieldIds.fingerprint(
        "bounded-generated-tool-promotion-receipt/v1",
        evidenceId,
        toolId,
        artifactId,
        recordFingerprint,
        trialEvidenceId,
        promotionPolicyFingerprint,
        novelAdmissionEvidenceId,
        canaryReadinessEvidenceId,
        promotionSealId,
        *reviewerEvidenceFingerprints.sorted().map { "reviewer:$it" }.toTypedArray(),
        *activationActorEvidenceFingerprints.sorted().map { "activation:$it" }.toTypedArray(),
    )

    override val activationAllowed: Boolean = false
}

internal fun boundedGeneratedToolPromotionEvidenceId(
    toolId: String,
    artifactId: String,
    recordFingerprint: String,
    trialEvidenceId: String,
    promotionPolicyFingerprint: String,
    novelAdmissionEvidenceId: String,
    canaryReadinessEvidenceId: String,
    promotionSealId: String,
    reviewerEvidenceFingerprints: List<String>,
    activationActorEvidenceFingerprints: List<String>,
): String = StableFieldIds.fingerprint(
    "bounded-generated-tool-promotion-evidence/v1",
    toolId,
    artifactId,
    recordFingerprint,
    trialEvidenceId,
    promotionPolicyFingerprint,
    novelAdmissionEvidenceId,
    canaryReadinessEvidenceId,
    promotionSealId,
    *reviewerEvidenceFingerprints.sorted().map { "reviewer:$it" }.toTypedArray(),
    *activationActorEvidenceFingerprints.sorted().map { "activation:$it" }.toTypedArray(),
)

internal fun GeneratedToolActivationReceipt.promotionAuditReason(): String = when (this) {
    is GeneratedToolPromotionReceipt -> "j03-promotion-evidence:$evidenceId"
    is BoundedGeneratedToolPromotionReceipt -> "bounded-generated-tool-promotion-evidence:$evidenceId"
}

/**
 * J10 durable lifecycle state. Legacy J03 receipt semantics remain byte/fingerprint compatible;
 * bounded receipts use a distinct state-id domain and are still non-authoritative evidence only.
 */
data class GeneratedToolPersistentState(
    val record: GeneratedToolRecord,
    val auditEntries: List<GeneratedToolAuditEntry>,
    val trialEvidence: GeneratedToolTrialEvidence,
    val promotionReceipt: GeneratedToolPromotionReceipt? = null,
    val boundedPromotionReceipt: BoundedGeneratedToolPromotionReceipt? = null,
) {
    init {
        val toolId = record.manifest.toolId
        require(trialEvidence.toolId == toolId) {
            "Persisted trial evidence belongs to another generated tool"
        }
        require(promotionReceipt == null || boundedPromotionReceipt == null) {
            "Persisted generated tool may carry exactly one promotion receipt kind"
        }
        GeneratedToolStateIntegrity.requireValidAudit(record, auditEntries)

        val receipt: GeneratedToolActivationReceipt? = promotionReceipt ?: boundedPromotionReceipt
        val promotionId = record.promotionEvidenceId
        if (promotionId == null) {
            require(receipt == null) {
                "Persisted promotion receipt requires a record promotion evidence id"
            }
        } else {
            val exactReceipt = requireNotNull(receipt) {
                "Persisted promoted lifecycle state requires a full promotion receipt"
            }
            require(!exactReceipt.activationAllowed) {
                "Persisted promotion receipt must remain non-authoritative"
            }
            require(exactReceipt.evidenceId == promotionId) {
                "Persisted promotion receipt id does not match generated tool record"
            }
            require(exactReceipt.toolId == toolId) {
                "Persisted promotion receipt belongs to another generated tool"
            }
            require(exactReceipt.trialEvidenceId == trialEvidence.id) {
                "Persisted promotion receipt does not match exact trial evidence"
            }
            val originalTrialRecord = record.copy(
                state = GeneratedToolState.TRIAL,
                promotionEvidenceId = null,
            )
            require(exactReceipt.recordFingerprint == originalTrialRecord.promotionRecordFingerprint()) {
                "Persisted promotion receipt does not bind the original TRIAL record"
            }
            val lastPromotion = auditEntries.lastOrNull { it.action == GeneratedToolAuditAction.PROMOTED }
            require(lastPromotion != null) {
                "Persisted promotion receipt requires a PROMOTED audit entry"
            }
            require(lastPromotion.reason == exactReceipt.promotionAuditReason()) {
                "Persisted promotion receipt does not match the last promotion audit"
            }
        }

        if (record.state == GeneratedToolState.ACTIVE) {
            require(receipt != null) { "ACTIVE restore requires a full promotion receipt" }
            require(auditEntries.last().action == GeneratedToolAuditAction.PROMOTED) {
                "ACTIVE restore must end at the promotion audit entry"
            }
            require(trialEvidence.stats.safetyViolations == 0) {
                "ACTIVE restore cannot contain trial safety violations"
            }
        }
    }

    val id: String = if (boundedPromotionReceipt == null) {
        // Preserve the exact legacy v1 identity domain for all pre-V1.2 states.
        StableFieldIds.fingerprint(
            "generated-tool-persistent-state/v1",
            record.auditFingerprint(),
            auditEntries.last().id,
            trialEvidence.id,
            promotionReceipt?.id.orEmpty(),
        )
    } else {
        StableFieldIds.fingerprint(
            "generated-tool-persistent-state/v2",
            record.auditFingerprint(),
            auditEntries.last().id,
            trialEvidence.id,
            "BOUNDED",
            boundedPromotionReceipt.id,
        )
    }
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
 * V1.2 continues to re-register ACTIVE providers only with an exact non-activating J03 receipt;
 * bounded ACTIVE receipt restoration is intentionally withheld until the later guarded restore gate.
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
            state.boundedPromotionReceipt?.let { require(!it.activationAllowed) }
            if (state.record.state == GeneratedToolState.ACTIVE) {
                require(state.boundedPromotionReceipt == null) {
                    "Bounded ACTIVE rehydration is unavailable before the guarded bounded restore gate"
                }
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
