package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleCoupling
import app.lifeos.core.model.ModuleCouplingRecord
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.ModuleOutcome
import app.lifeos.core.model.ModuleProcessingRecord
import app.lifeos.core.model.ModuleReplayEnvelope
import app.lifeos.core.model.ModuleUtilitySnapshot
import app.lifeos.core.model.ModuleWorkspaceEntry
import app.lifeos.core.model.ModuleWorkspaceSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime-side evidence layer shared by B14-B18.
 * It consumes canonical ModuleProcessingRecord evidence instead of introducing a parallel module stack.
 */
class ModuleEvidenceRuntime {
    private val mutex = Mutex()
    private val processing = linkedMapOf<String, ModuleProcessingRecord>()
    private val couplings = linkedMapOf<String, ModuleCouplingRecord>()
    private val outcomes = linkedMapOf<String, ModuleOutcome>()
    private val utilities = linkedMapOf<String, ModuleUtilitySnapshot>()

    suspend fun recordProcessing(record: ModuleProcessingRecord) = mutex.withLock {
        val key = record.processingId.value
        val existing = processing[key]
        require(existing == null || existing == record) { "Processing evidence is immutable: $key" }
        processing[key] = record
        utilities.putIfAbsent(record.module.stableFingerprint, ModuleUtilitySnapshot.empty(record.module))
    }

    suspend fun recordCoupling(coupling: ModuleCoupling, record: ModuleProcessingRecord) = mutex.withLock {
        val evidence = ModuleCouplingRecord(coupling, record)
        val key = coupling.stableFingerprint
        val existing = couplings[key]
        require(existing == null || existing == evidence) { "Coupling evidence is immutable: $key" }
        couplings[key] = evidence
    }

    suspend fun recordOutcome(outcome: ModuleOutcome) = mutex.withLock {
        val key = outcome.stableFingerprint
        val existing = outcomes[key]
        require(existing == null || existing == outcome) { "Outcome evidence is immutable: $key" }
        if (existing == null) {
            outcomes[key] = outcome
            val fingerprint = outcome.processing.module.stableFingerprint
            val snapshot = utilities[fingerprint] ?: ModuleUtilitySnapshot.empty(outcome.processing.module)
            utilities[fingerprint] = snapshot.observe(outcome)
        }
    }

    suspend fun workspace(activeModules: Collection<ModuleIdentity>): ModuleWorkspaceSnapshot = mutex.withLock {
        val entries = activeModules
            .associateBy { it.stableFingerprint }
            .values
            .map { module ->
                val latest = processing.values
                    .asSequence()
                    .filter { it.module.stableFingerprint == module.stableFingerprint }
                    .maxByOrNull { it.processingId.value }
                ModuleWorkspaceEntry(
                    module = module,
                    latestProcessingId = latest?.processingId,
                    latestTraceId = latest?.traceId,
                    outputPhotonIds = latest?.outputPhotonIds.orEmpty(),
                    utility = utilities[module.stableFingerprint] ?: ModuleUtilitySnapshot.empty(module),
                )
            }
        ModuleWorkspaceSnapshot(entries)
    }

    suspend fun replayEnvelopes(): List<ModuleReplayEnvelope> = mutex.withLock {
        processing.values
            .sortedBy { it.processingId.value }
            .map { record ->
                ModuleReplayEnvelope(
                    processing = record,
                    expectedOutputStateHash = record.outputStateHash,
                    expectedOutputPhotonIds = record.outputPhotonIds,
                )
            }
    }

    suspend fun verifyReplay(replayed: Collection<ModuleProcessingRecord>): Boolean = mutex.withLock {
        val byId = replayed.associateBy { it.processingId.value }
        processing.values.all { original ->
            byId[original.processingId.value]?.let { candidate ->
                ModuleReplayEnvelope(original, original.outputStateHash, original.outputPhotonIds).matches(candidate)
            } == true
        }
    }
}
