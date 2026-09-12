package app.lifeos.core.runtime

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.CognitiveBranch
import app.lifeos.core.model.CognitiveBranchStatus
import app.lifeos.core.model.CognitiveIntegrationRecord
import app.lifeos.core.model.DeterminismContext
import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.LogicalTick
import app.lifeos.core.model.ModuleProcessingRecord
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import kotlinx.coroutines.CancellationException

data class CausalCognitionEngineConfig(
    val runtimeVersion: String = "lifeos-causal-runtime-v1",
    val policyVersion: String = "owner-policy-current",
    val maxTotalDerivedPhotons: Int = 128,
) {
    init {
        require(runtimeVersion.isNotBlank()) { "Runtime version must not be blank" }
        require(policyVersion.isNotBlank()) { "Policy version must not be blank" }
        require(maxTotalDerivedPhotons in 1..4096) { "maxTotalDerivedPhotons must be in 1..4096" }
    }
}

data class CausalCognitionResult(
    val traceId: CausalTraceId,
    val attraction: FieldAttractionPlan?,
    val ledgerEntry: CausalLedgerEntry,
    val emittedPhotons: List<Photon>,
    val influences: List<FieldInfluence>,
    val failures: List<RuntimeFailure>,
    val replayed: Boolean,
)

/**
 * Universal immutable fan-out/fan-in cognition path.
 *
 * - the source Photon is never changed;
 * - attracted modules get deterministic branches and replay context;
 * - module outputs receive deterministic ids and causal lineage;
 * - complementary successful branches are integrated into a new canonical Photon;
 * - an existing trace is never executed twice by the same ledger.
 */
class CausalCognitionEngine(
    private val attractionEngine: FieldAttractionEngine = FieldAttractionEngine(),
    private val ledger: CausalLedgerStore = InMemoryCausalLedgerStore(),
    private val config: CausalCognitionEngineConfig = CausalCognitionEngineConfig(),
) {
    suspend fun process(source: Photon, modules: Collection<CognitiveModule>): CausalCognitionResult {
        val inputStateHash = CanonicalPhotonState.inputHash(source)
        val traceId = StableCognitiveIds.trace(source.id, source.revision, inputStateHash)

        ledger.load(traceId)?.let { existing ->
            return CausalCognitionResult(
                traceId = traceId,
                attraction = null,
                ledgerEntry = existing,
                emittedPhotons = emptyList(),
                influences = emptyList(),
                failures = emptyList(),
                replayed = true,
            )
        }

        val attraction = attractionEngine.plan(source, modules)
        val branches = mutableListOf<CognitiveBranch>()
        val records = mutableListOf<ModuleProcessingRecord>()
        val emitted = mutableListOf<Photon>()
        val influences = mutableListOf<FieldInfluence>()
        val failures = mutableListOf<RuntimeFailure>()

        attraction.selected.forEachIndexed { ordinal, decision ->
            val descriptor = decision.module.descriptor
            val identity = descriptor.identity
            val branchId = StableCognitiveIds.branch(
                traceId = traceId,
                parentPhotonId = source.id,
                moduleKey = identity.stableFingerprint,
                ordinal = ordinal,
            )
            val createdBranch = CognitiveBranch(
                branchId = branchId,
                traceId = traceId,
                parentPhotonId = source.id,
                parentRevision = source.revision,
                ordinal = ordinal,
                module = identity,
                inputStateHash = inputStateHash,
            ).processing()

            val context = DeterminismContext(
                traceId = traceId,
                logicalTick = LogicalTick((ordinal + 1).toLong()),
                inputHash = inputStateHash,
                parentStateHash = inputStateHash,
                runtimeVersion = config.runtimeVersion,
                policyVersion = config.policyVersion,
                randomSeed = stableSeed(traceId, identity.stableFingerprint, ordinal),
                parametersHash = descriptorParametersHash(descriptor),
            )

            val moduleResult = try {
                decision.module.processor.process(source, context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: error::class.simpleName ?: "Module failure"
                failures += RuntimeFailure(
                    category = RuntimeFailureCategory.FIELD,
                    source = identity.moduleId,
                    message = message,
                    photonId = source.id,
                )
                val failureHash = StableCognitiveIds.stateHash(
                    "module-failure",
                    identity.stableFingerprint,
                    message,
                )
                branches += createdBranch.rejected(emptyList(), failureHash)
                return@forEachIndexed
            }

            if (moduleResult.outputPhotons.size > descriptor.maxOutputs ||
                emitted.size + moduleResult.outputPhotons.size > config.maxTotalDerivedPhotons
            ) {
                val message = "Module output budget exceeded"
                failures += RuntimeFailure(
                    category = RuntimeFailureCategory.INVARIANT,
                    source = identity.moduleId,
                    message = message,
                    photonId = source.id,
                )
                val rejectedHash = StableCognitiveIds.stateHash(
                    "output-budget",
                    identity.stableFingerprint,
                    moduleResult.outputPhotons.size.toString(),
                )
                branches += createdBranch.rejected(emptyList(), rejectedHash)
                return@forEachIndexed
            }

            val normalizedInfluences = moduleResult.influences.map { influence ->
                influence.copy(
                    module = identity.moduleId,
                    photonId = source.id,
                )
            }
            influences += normalizedInfluences

            val normalizedOutputs = moduleResult.outputPhotons.mapIndexed { outputOrdinal, candidate ->
                CanonicalPhotonState.normalizeDerived(
                    source = source,
                    traceId = traceId,
                    branchId = branchId,
                    module = identity,
                    outputOrdinal = outputOrdinal,
                    candidate = candidate,
                )
            }
            val outputStateHash = CanonicalPhotonState.outputsHash(normalizedOutputs)
            val readyBranch = createdBranch.ready(
                outputs = normalizedOutputs.map { it.id },
                stateHash = outputStateHash,
            )
            branches += readyBranch
            emitted += normalizedOutputs
            records += ModuleProcessingRecord(
                processingId = StableCognitiveIds.moduleProcessing(
                    traceId = traceId,
                    branchId = branchId,
                    moduleFingerprint = identity.stableFingerprint,
                    inputPhotonId = source.id,
                    inputRevision = source.revision,
                ),
                traceId = traceId,
                branchId = branchId,
                module = identity,
                inputPhotonId = source.id,
                inputRevision = source.revision,
                context = context,
                outputPhotonIds = normalizedOutputs.map { it.id },
                outputStateHash = outputStateHash,
            )
        }

        val successful = branches.filter { it.status == CognitiveBranchStatus.READY_FOR_CONVERGENCE }
        val integratedPhoton = if (successful.isNotEmpty()) {
            buildIntegrationPhoton(source, traceId, successful, emitted)
        } else {
            null
        }
        if (integratedPhoton != null) emitted += integratedPhoton

        val finalBranches = branches.map { branch ->
            if (branch.status == CognitiveBranchStatus.READY_FOR_CONVERGENCE) branch.converged() else branch
        }
        val integration = integratedPhoton?.let { photon ->
            val branchIds = successful.map { it.branchId }
            CognitiveIntegrationRecord(
                convergenceId = StableCognitiveIds.convergence(traceId, branchIds),
                traceId = traceId,
                branchIds = branchIds,
                contributingBranchIds = branchIds,
                inputStateHash = inputStateHash,
                outputStateHash = CanonicalPhotonState.semanticHash(photon),
                integratedPhotonId = photon.id,
                reasonFingerprint = StableCognitiveIds.fingerprint(
                    "complementary-integration",
                    traceId.value,
                    *branchIds.map { it.value }.sorted().toTypedArray(),
                ),
            )
        }

        val ledgerEntry = CausalLedgerEntry(
            traceId = traceId,
            rootPhotonId = source.id,
            attraction = attraction.toLedgerRecords(),
            branches = finalBranches,
            processingRecords = records.toList(),
            integration = integration,
            emittedPhotonIds = emitted.map { it.id }.distinct(),
        )
        ledger.append(ledgerEntry)

        return CausalCognitionResult(
            traceId = traceId,
            attraction = attraction,
            ledgerEntry = ledgerEntry,
            emittedPhotons = emitted.toList(),
            influences = influences.toList(),
            failures = failures.toList(),
            replayed = false,
        )
    }

    private fun buildIntegrationPhoton(
        source: Photon,
        traceId: CausalTraceId,
        branches: List<CognitiveBranch>,
        moduleOutputs: List<Photon>,
    ): Photon {
        val canonicalBranches = branches.sortedBy { it.branchId.value }
        val canonicalOutputs = moduleOutputs.sortedBy { it.id.value }
        val identityHash = StableCognitiveIds.fingerprint(
            traceId.value,
            *canonicalBranches.map { it.branchId.value }.toTypedArray(),
            *canonicalOutputs.map { it.id.value }.toTypedArray(),
        )
        val integratedId = PhotonId("integration-$identityHash")
        val content = buildString {
            appendLine("trace=${traceId.value}")
            appendLine("source=${source.id.value}@${source.revision}")
            appendLine("branches=${canonicalBranches.joinToString(",") { it.branchId.value }}")
            appendLine("modules=${canonicalBranches.joinToString(",") { it.module.moduleId + "@" + it.module.version }}")
            append("outputs=${canonicalOutputs.joinToString(",") { it.id.value }}")
        }
        val confidenceValues = listOf(source.confidence) + canonicalOutputs.map { it.confidence }
        return Photon(
            id = integratedId,
            revision = 1,
            content = content,
            mimeType = "application/vnd.lifeos.cognitive-integration+text",
            phase = PhotonPhase.CONVERGED,
            semanticMass = source.semanticMass + canonicalOutputs.sumOf { it.semanticMass },
            energy = maxOf(source.energy, canonicalOutputs.maxOfOrNull { it.energy } ?: 0.0),
            confidence = confidenceValues.average().coerceIn(0.0, 1.0),
            provenance = Provenance(
                source = "causal-cognition",
                actor = "lifeos",
                createdAt = source.provenance.createdAt,
                parentIds = setOf(source.id) + canonicalOutputs.map { it.id },
            ),
            relations = setOf(
                PhotonRelation(source.id, RelationType.DERIVED_FROM),
            ) + canonicalOutputs.map { PhotonRelation(it.id, RelationType.REFERENCES) },
            tags = setOf(
                "cognitive-integration",
                "causal-trace:${traceId.value}",
            ),
        )
    }

    private fun stableSeed(traceId: CausalTraceId, moduleFingerprint: String, ordinal: Int): Long {
        val hex = StableCognitiveIds.fingerprint(
            "seed",
            traceId.value,
            moduleFingerprint,
            ordinal.toString(),
        ).take(16)
        return java.lang.Long.parseUnsignedLong(hex, 16)
    }

    private fun descriptorParametersHash(descriptor: CognitiveModuleDescriptor) = StableCognitiveIds.stateHash(
        descriptor.identity.stableFingerprint,
        descriptor.acceptedMimeTypes.sorted().joinToString("\u0000"),
        descriptor.preferredTags.sorted().joinToString("\u0000"),
        descriptor.requiredTags.sorted().joinToString("\u0000"),
        descriptor.baseAttraction.toString(),
        descriptor.minimumAttraction.toString(),
        descriptor.maxOutputs.toString(),
    )
}
