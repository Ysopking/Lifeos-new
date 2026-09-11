package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.evolution.NovelCapabilityPromotionStore

/**
 * V1.5 boot-only rehydrator for snapshots that contain bounded ACTIVE tools.
 *
 * Persisted receipts remain evidence only. Before RAM/provider restoration every bounded ACTIVE
 * record is re-bound to the exact executable artifact and the exact durable Novel Canary seal,
 * reservation ledger and outcome ledger that authorized the previous live promotion.
 */
internal class BoundedGeneratedToolStateRehydrator(
    private val repository: GeneratedToolStateRepository,
    private val tools: GeneratedToolRegistry,
    private val trialLedger: GeneratedToolTrialLedger,
    private val capabilityRegistry: CapabilityRegistry?,
    private val artifacts: GeneratedToolArtifactRepository,
    private val novelPromotionStore: NovelCapabilityPromotionStore,
    private val promotionPolicy: GeneratedToolPromotionPolicy = GeneratedToolPromotionPolicy(),
) {
    suspend fun rehydrate(states: List<GeneratedToolPersistentState>): GeneratedToolRehydrationReport {
        require(tools.snapshot().isEmpty()) {
            "Bounded generated-tool rehydration requires an empty registry"
        }
        require(trialLedger.isEmpty()) {
            "Bounded generated-tool rehydration requires an empty trial ledger"
        }
        val existingGeneratedProviders = capabilityRegistry
            ?.all(includeUnavailable = true)
            ?.filter { it.providerType == ProviderType.GENERATED_TOOL }
            .orEmpty()
        require(existingGeneratedProviders.isEmpty()) {
            "Bounded generated-tool rehydration requires no pre-registered generated providers"
        }

        val durable = repository.loadAll().sortedBy { it.record.manifest.toolId }
        require(durable == states.sortedBy { it.record.manifest.toolId }) {
            "Generated-tool durable state changed during bounded boot preflight"
        }
        require(durable.map { it.record.manifest.toolId }.distinct().size == durable.size) {
            "Durable generated-tool state contains duplicate tool identities"
        }

        for (state in durable) {
            GeneratedToolStateIntegrity.requireValidAudit(state.record, state.auditEntries)
            state.promotionReceipt?.let { require(!it.activationAllowed) }
            state.boundedPromotionReceipt?.let { require(!it.activationAllowed) }
            if (state.record.state != GeneratedToolState.ACTIVE) continue

            requirePromotionEligible(state.record, state.trialEvidence.stats)
            val legacy = state.promotionReceipt
            val bounded = state.boundedPromotionReceipt
            require((legacy == null) xor (bounded == null)) {
                "ACTIVE generated tool requires exactly one promotion receipt kind"
            }
            if (legacy != null) {
                require(legacy.promotionPolicyFingerprint == promotionPolicy.fingerprint()) {
                    "ACTIVE persisted J03 receipt uses another promotion policy"
                }
            } else {
                verifyBoundedActive(state, requireNotNull(bounded))
            }
        }

        for (state in durable) {
            tools.restore(state)
            trialLedger.restore(state.trialEvidence)
        }

        var activeProviders = 0
        for (state in durable.filter { it.record.state == GeneratedToolState.ACTIVE }) {
            if (capabilityRegistry != null) {
                val descriptor = state.toCapabilityDescriptor()
                state.promotionReceipt?.let { legacy ->
                    capabilityRegistry.registerGeneratedRestored(descriptor, state.record, legacy)
                } ?: capabilityRegistry.registerGeneratedRestoredBounded(
                    descriptor = descriptor,
                    activeRecord = state.record,
                    receipt = requireNotNull(state.boundedPromotionReceipt),
                )
                activeProviders += 1
            }
        }

        return GeneratedToolRehydrationReport(
            restoredTools = durable.size,
            restoredTrialResults = durable.sumOf { it.trialEvidence.stats.trials },
            restoredActiveProviders = activeProviders,
        )
    }

    private suspend fun verifyBoundedActive(
        state: GeneratedToolPersistentState,
        receipt: BoundedGeneratedToolPromotionReceipt,
    ) {
        require(receipt.promotionPolicyFingerprint == promotionPolicy.fingerprint()) {
            "ACTIVE bounded receipt uses another promotion policy"
        }
        require(GeneratedToolArtifact.isBoundedSourceHash(state.record.manifest.sourceHash)) {
            "ACTIVE bounded restore requires a bounded generated-tool source hash"
        }
        require(state.record.manifest.permissions.isEmpty()) {
            "ACTIVE bounded restore currently permits only zero-permission generated tools"
        }

        val artifact = requireNotNull(artifacts.load(receipt.toolId)) {
            "ACTIVE bounded restore is missing its executable artifact"
        }
        require(artifact.id == receipt.artifactId) {
            "ACTIVE bounded restore artifact id differs from promotion receipt"
        }
        require(artifact.matches(state.record)) {
            "ACTIVE bounded restore artifact no longer matches generated-tool manifest"
        }
        require(!artifact.activationAllowed)

        val seal = requireNotNull(
            novelPromotionStore.novelPromotionSeal(receipt.novelAdmissionEvidenceId)
        ) { "ACTIVE bounded restore is missing its durable Novel Canary promotion seal" }
        require(!seal.activationAllowed)
        require(seal.id == receipt.promotionSealId) {
            "ACTIVE bounded restore seal differs from promotion receipt"
        }
        require(seal.admissionEvidenceId == receipt.novelAdmissionEvidenceId)
        require(seal.toolId == receipt.toolId)
        require(seal.artifactId == receipt.artifactId)
        require(seal.readinessEvidenceId == receipt.canaryReadinessEvidenceId)

        val reservations = novelPromotionStore.novelReservations(receipt.novelAdmissionEvidenceId)
        val outcomes = novelPromotionStore.novelOutcomes(receipt.novelAdmissionEvidenceId)
        require(novelPromotionStore.novelKillSwitch(receipt.novelAdmissionEvidenceId) == null) {
            "ACTIVE bounded restore cannot replay a stopped Novel Canary"
        }
        require(reservations.size == seal.expectedReservedInvocations) {
            "ACTIVE bounded restore reservation count differs from promotion seal"
        }
        require(outcomes.size == seal.expectedReservedInvocations) {
            "ACTIVE bounded restore outcome count differs from promotion seal"
        }
        require(reservations.all {
            it.toolId == receipt.toolId && it.candidateRecordFingerprint == seal.candidateRecordFingerprint
        }) { "ACTIVE bounded restore reservations differ from sealed candidate" }
        require(outcomes.all {
            it.toolId == receipt.toolId &&
                it.candidateRecordFingerprint == seal.candidateRecordFingerprint &&
                it.success &&
                it.producedExpectedOutput &&
                !it.safetyViolation
        }) { "ACTIVE bounded restore outcomes no longer satisfy sealed readiness" }
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
